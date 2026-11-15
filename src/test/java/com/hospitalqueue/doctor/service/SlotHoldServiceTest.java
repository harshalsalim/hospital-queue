package com.hospitalqueue.doctor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;

import com.hospitalqueue.common.exception.ApiException;
import com.hospitalqueue.doctor.entity.Slot;
import com.hospitalqueue.doctor.repository.SlotCounterRepository;
import com.hospitalqueue.doctor.repository.SlotCounterRepository.SlotState;
import com.hospitalqueue.doctor.repository.SlotRepository;
import com.hospitalqueue.doctor.service.SlotHoldService.SlotHold;

/**
 * Unit tests for {@link SlotHoldService} with a fixed clock (2026-11-12 10:00 in Asia/Kolkata) and a fake Redis.
 * The Lua script itself is proven against a real Redis in SlotHoldIntegrationTest.
 */
@ExtendWith(MockitoExtension.class)
class SlotHoldServiceTest {

    private static final UUID SLOT_ID = UUID.randomUUID();
    private static final UUID HOLD_ID = UUID.randomUUID();
    private static final UUID HOSPITAL_ID = UUID.randomUUID();
    private static final UUID DOCTOR_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-12T04:30:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 12);
    private static final String KEY = "hq:hold:" + SLOT_ID;

    @Mock
    private SlotRepository slotRepository;

    @Mock
    private SlotCounterRepository slotCounterRepository;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ZSetOperations<String, String> zSetOperations;

    private SlotHoldService slotHoldService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneId.of("Asia/Kolkata"));
        lenient().when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        slotHoldService = new SlotHoldService(slotRepository, slotCounterRepository, redisTemplate, clock, 900);
    }

    private void futureSlotWithState(String status, int booked, int max) {
        when(slotRepository.findById(SLOT_ID)).thenReturn(Optional.of(
                new Slot(HOSPITAL_ID, DOCTOR_ID, TODAY.plusDays(1), LocalTime.of(9, 0), LocalTime.of(12, 0), max)));
        when(slotCounterRepository.readState(SLOT_ID)).thenReturn(Optional.of(new SlotState(status, booked, max)));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void scriptReturns(Long value) {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any())).thenReturn(value);
    }

    private static void assertApiError(Throwable thrown, HttpStatus status, String code) {
        assertThat(thrown).isInstanceOf(ApiException.class);
        ApiException exception = (ApiException) thrown;
        assertThat(exception.getStatus()).isEqualTo(status);
        assertThat(exception.getCode()).isEqualTo(code);
    }

    // ---- acquire ----

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void acquire_runsTheScriptWithTheFreeTokenCountDurationAndHoldId() {
        futureSlotWithState("AVAILABLE", 5, 30);
        long expiry = NOW.toEpochMilli() + 900_000;
        scriptReturns(expiry);

        SlotHold hold = slotHoldService.acquire(SLOT_ID, HOLD_ID);

        assertThat(hold.slotId()).isEqualTo(SLOT_ID);
        assertThat(hold.holdId()).isEqualTo(HOLD_ID);
        assertThat(hold.hospitalId()).isEqualTo(HOSPITAL_ID);
        assertThat(hold.doctorId()).isEqualTo(DOCTOR_ID);
        assertThat(hold.expiresAt()).isEqualTo(Instant.ofEpochMilli(expiry));
        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of(KEY)), eq("25"), eq("900000"), eq(HOLD_ID.toString()));
    }

    @Test
    void acquire_unknownSlotThrowsNotFoundWithoutTouchingRedis() {
        when(slotRepository.findById(SLOT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> slotHoldService.acquire(SLOT_ID, HOLD_ID))
                .satisfies(thrown -> assertApiError(thrown, HttpStatus.NOT_FOUND, "NOT_FOUND"));

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void acquire_aSessionThatHasEndedIsRejectedWithoutTouchingRedis() {
        when(slotRepository.findById(SLOT_ID)).thenReturn(Optional.of(
                new Slot(HOSPITAL_ID, DOCTOR_ID, TODAY, LocalTime.of(8, 0), LocalTime.of(9, 30), 30)));

        assertThatThrownBy(() -> slotHoldService.acquire(SLOT_ID, HOLD_ID))
                .satisfies(thrown -> assertApiError(thrown, HttpStatus.CONFLICT, "SLOT_ENDED"));

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void acquire_aPausedSessionIsRejectedWithoutTouchingRedis() {
        futureSlotWithState("PAUSED", 3, 30);

        assertThatThrownBy(() -> slotHoldService.acquire(SLOT_ID, HOLD_ID))
                .satisfies(thrown -> assertApiError(thrown, HttpStatus.CONFLICT, "SLOT_PAUSED"));

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void acquire_aFullyBookedSessionIsRejectedWithoutTouchingRedis() {
        futureSlotWithState("AVAILABLE", 30, 30);

        assertThatThrownBy(() -> slotHoldService.acquire(SLOT_ID, HOLD_ID))
                .satisfies(thrown -> assertApiError(thrown, HttpStatus.CONFLICT, "SLOT_FULL"));

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void acquire_aSessionDeletedBeforeTheStateReadIsNotFound() {
        when(slotRepository.findById(SLOT_ID)).thenReturn(Optional.of(
                new Slot(HOSPITAL_ID, DOCTOR_ID, TODAY.plusDays(1), LocalTime.of(9, 0), LocalTime.of(12, 0), 30)));
        when(slotCounterRepository.readState(SLOT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> slotHoldService.acquire(SLOT_ID, HOLD_ID))
                .satisfies(thrown -> assertApiError(thrown, HttpStatus.NOT_FOUND, "NOT_FOUND"));

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void acquire_whenEveryFreeTokenIsAlreadyHeldReportsTemporarilyFull() {
        futureSlotWithState("AVAILABLE", 5, 30);
        scriptReturns(-1L);

        assertThatThrownBy(() -> slotHoldService.acquire(SLOT_ID, HOLD_ID))
                .satisfies(thrown -> assertApiError(thrown, HttpStatus.CONFLICT, "SLOT_TEMPORARILY_FULL"));
    }

    @Test
    void acquire_failsClosedWithServiceUnavailableWhenRedisReturnsNothing() {
        futureSlotWithState("AVAILABLE", 5, 30);
        scriptReturns(null);

        assertThatThrownBy(() -> slotHoldService.acquire(SLOT_ID, HOLD_ID))
                .satisfies(thrown -> assertApiError(thrown, HttpStatus.SERVICE_UNAVAILABLE, "HOLD_UNAVAILABLE"));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void acquire_failsClosedWithServiceUnavailableWhenRedisIsDown() {
        futureSlotWithState("AVAILABLE", 5, 30);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("redis is down"));

        assertThatThrownBy(() -> slotHoldService.acquire(SLOT_ID, HOLD_ID))
                .satisfies(thrown -> assertApiError(thrown, HttpStatus.SERVICE_UNAVAILABLE, "HOLD_UNAVAILABLE"));
    }

    // ---- release ----

    @Test
    void release_removesTheHoldFromTheSessionsSortedSet() {
        slotHoldService.release(SLOT_ID, HOLD_ID);

        verify(zSetOperations).remove(KEY, HOLD_ID.toString());
    }

    @Test
    void release_neverFailsWhenRedisIsDown() {
        doThrow(new RedisConnectionFailureException("redis is down")).when(zSetOperations).remove(anyString(), any());

        assertThatCode(() -> slotHoldService.release(SLOT_ID, HOLD_ID)).doesNotThrowAnyException();
    }

    // ---- expiryOf ----

    @Test
    void expiryOf_returnsTheExpiryOfALiveHold() {
        long expiry = NOW.toEpochMilli() + 60_000;
        when(zSetOperations.score(KEY, HOLD_ID.toString())).thenReturn((double) expiry);

        assertThat(slotHoldService.expiryOf(SLOT_ID, HOLD_ID)).contains(Instant.ofEpochMilli(expiry));
    }

    @Test
    void expiryOf_isEmptyWhenTheHoldDoesNotExist() {
        when(zSetOperations.score(KEY, HOLD_ID.toString())).thenReturn(null);

        assertThat(slotHoldService.expiryOf(SLOT_ID, HOLD_ID)).isEmpty();
    }

    @Test
    void expiryOf_isEmptyWhenTheHoldHasAlreadyLapsed() {
        when(zSetOperations.score(KEY, HOLD_ID.toString())).thenReturn((double) (NOW.toEpochMilli() - 1));

        assertThat(slotHoldService.expiryOf(SLOT_ID, HOLD_ID)).isEmpty();
    }

    @Test
    void expiryOf_reportsServiceUnavailableWhenRedisIsDown() {
        when(zSetOperations.score(KEY, HOLD_ID.toString())).thenThrow(new RedisConnectionFailureException("redis is down"));

        assertThatThrownBy(() -> slotHoldService.expiryOf(SLOT_ID, HOLD_ID))
                .satisfies(thrown -> assertApiError(thrown, HttpStatus.SERVICE_UNAVAILABLE, "HOLD_UNAVAILABLE"));
    }
}
