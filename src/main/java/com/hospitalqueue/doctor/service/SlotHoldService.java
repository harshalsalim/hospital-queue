package com.hospitalqueue.doctor.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.hospitalqueue.common.exception.ApiException;
import com.hospitalqueue.doctor.entity.Slot;
import com.hospitalqueue.doctor.entity.SlotStatus;
import com.hospitalqueue.doctor.repository.SlotCounterRepository;
import com.hospitalqueue.doctor.repository.SlotCounterRepository.SlotState;
import com.hospitalqueue.doctor.repository.SlotRepository;

/**
 * Temporary holds on a session's tokens while a patient pays.
 *
 * <p>A hold is a soft reservation kept in Redis for a limited time (default 15 minutes). It is kept per session in
 * a sorted set whose members are hold ids and whose scores are their expiry times. One Lua script does the whole
 * acquire step atomically inside Redis: purge expired holds, count the live ones, and add a new one only while
 * live holds are fewer than the tokens still free. The database remains the final judge: a token is only truly
 * issued by the atomic counter in {@link SlotBookingService}.
 *
 * <p>Failing closed: if Redis is unreachable, new holds are refused with 503 instead of letting patients pay for a
 * token that might not be available.
 */
@Service
public class SlotHoldService {

    /**
     * A successful hold.
     *
     * @param slotId     the held session
     * @param hospitalId hospital of the session
     * @param doctorId   doctor of the session
     * @param holdId     id of this hold (the checkout id)
     * @param expiresAt  when the hold lapses if the patient has not paid
     */
    public record SlotHold(UUID slotId, UUID hospitalId, UUID doctorId, UUID holdId, Instant expiresAt) {
    }

    private static final Logger log = LoggerFactory.getLogger(SlotHoldService.class);

    private static final String KEY_PREFIX = "hq:hold:";

    /**
     * KEYS[1] = hold set of the session; ARGV[1] = tokens still free; ARGV[2] = hold duration in milliseconds;
     * ARGV[3] = hold id. Returns the hold's expiry in epoch milliseconds, or -1 when no free token can be held.
     * Time comes from the Redis server, so application servers with different clocks agree.
     */
    private static final String ACQUIRE_LUA = """
            local t = redis.call('TIME')
            local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', string.format('%.0f', now))
            if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[1]) then
                return -1
            end
            local expiry = now + tonumber(ARGV[2])
            redis.call('ZADD', KEYS[1], string.format('%.0f', expiry), ARGV[3])
            redis.call('PEXPIRE', KEYS[1], ARGV[2])
            return expiry
            """;

    private static final RedisScript<Long> ACQUIRE_SCRIPT = new DefaultRedisScript<>(ACQUIRE_LUA, Long.class);

    private final SlotRepository slotRepository;
    private final SlotCounterRepository slotCounterRepository;
    private final StringRedisTemplate redisTemplate;
    private final Clock clock;
    private final long holdMillis;

    public SlotHoldService(SlotRepository slotRepository,
                           SlotCounterRepository slotCounterRepository,
                           StringRedisTemplate redisTemplate,
                           Clock clock,
                           @Value("${app.booking.hold-seconds:900}") long holdSeconds) {
        this.slotRepository = slotRepository;
        this.slotCounterRepository = slotCounterRepository;
        this.redisTemplate = redisTemplate;
        this.clock = clock;
        this.holdMillis = holdSeconds * 1000;
    }

    /**
     * Holds one token of a session for a checkout.
     *
     * @param slotId the session
     * @param holdId id of the new hold (the checkout id)
     * @return the hold, including when it lapses
     * @throws ApiException 404 unknown session; 409 {@code SLOT_ENDED}, {@code SLOT_PAUSED}, {@code SLOT_FULL} (every
     *                      token is booked) or {@code SLOT_TEMPORARILY_FULL} (every free token is held by other
     *                      patients right now); 503 {@code HOLD_UNAVAILABLE} when Redis cannot be reached
     */
    public SlotHold acquire(UUID slotId, UUID holdId) {
        Slot slot = slotRepository.findById(slotId).orElseThrow(() -> ApiException.notFound("Slot", slotId));
        if (hasEnded(slot)) {
            throw ApiException.conflict("SLOT_ENDED", "This session has already ended");
        }

        SlotState state = slotCounterRepository.readState(slotId).orElseThrow(() -> ApiException.notFound("Slot", slotId));
        if (SlotStatus.PAUSED.name().equals(state.status())) {
            throw ApiException.conflict("SLOT_PAUSED", "Bookings are paused for this session");
        }
        int tokensStillFree = state.maxTokens() - state.bookedCount();
        if (tokensStillFree <= 0) {
            throw ApiException.conflict("SLOT_FULL", "All tokens for this session are booked");
        }

        long expiresAtMillis = runAcquireScript(slotId, holdId, tokensStillFree);
        if (expiresAtMillis < 0) {
            throw ApiException.conflict("SLOT_TEMPORARILY_FULL",
                    "All remaining tokens are being booked by other patients right now, please try again in a few minutes");
        }
        return new SlotHold(slotId, slot.getHospitalId(), slot.getDoctorId(), holdId, Instant.ofEpochMilli(expiresAtMillis));
    }

    /**
     * Gives a hold back, for example after payment succeeded or the checkout was cancelled. Best effort: if Redis
     * is unreachable the hold simply lapses by itself.
     *
     * @param slotId the session
     * @param holdId the hold to release
     */
    public void release(UUID slotId, UUID holdId) {
        try {
            redisTemplate.opsForZSet().remove(key(slotId), holdId.toString());
        } catch (DataAccessException unreachable) {
            log.warn("Could not release slot hold {}; it will lapse by itself", holdId);
        }
    }

    /**
     * Tells whether a hold is still alive.
     *
     * @param slotId the session
     * @param holdId the hold
     * @return when the hold lapses, or empty when it has lapsed, was released or never existed
     * @throws ApiException 503 {@code HOLD_UNAVAILABLE} when Redis cannot be reached
     */
    public Optional<Instant> expiryOf(UUID slotId, UUID holdId) {
        try {
            Double score = redisTemplate.opsForZSet().score(key(slotId), holdId.toString());
            if (score == null) {
                return Optional.empty();
            }
            Instant expiry = Instant.ofEpochMilli(score.longValue());
            return expiry.isAfter(clock.instant()) ? Optional.of(expiry) : Optional.empty();
        } catch (DataAccessException unreachable) {
            throw unavailable();
        }
    }

    private long runAcquireScript(UUID slotId, UUID holdId, int tokensStillFree) {
        try {
            Long result = redisTemplate.execute(ACQUIRE_SCRIPT, List.of(key(slotId)),
                    String.valueOf(tokensStillFree), String.valueOf(holdMillis), holdId.toString());
            if (result == null) {
                throw unavailable();
            }
            return result;
        } catch (DataAccessException unreachable) {
            throw unavailable();
        }
    }

    private boolean hasEnded(Slot slot) {
        LocalDateTime end = LocalDateTime.of(slot.getSlotDate(), slot.getEndTime());
        return !end.isAfter(LocalDateTime.now(clock));
    }

    private static String key(UUID slotId) {
        return KEY_PREFIX + slotId;
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "HOLD_UNAVAILABLE",
                "Booking is temporarily unavailable, please try again shortly");
    }
}
