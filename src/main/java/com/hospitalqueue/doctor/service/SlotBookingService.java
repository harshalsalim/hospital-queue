package com.hospitalqueue.doctor.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.OptionalInt;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hospitalqueue.common.exception.ApiException;
import com.hospitalqueue.doctor.dto.TokenAllocation;
import com.hospitalqueue.doctor.entity.Slot;
import com.hospitalqueue.doctor.entity.SlotStatus;
import com.hospitalqueue.doctor.repository.SlotCounterRepository;
import com.hospitalqueue.doctor.repository.SlotCounterRepository.SlotState;
import com.hospitalqueue.doctor.repository.SlotRepository;

/**
 * The doctor module's booking entry point for other modules: takes one token from a session.
 *
 * <p>The patient module calls {@link #takeToken(UUID)} inside its own transaction and stores the token
 * in that same transaction, so the counter and the token row succeed or fail together.
 */
@Service
public class SlotBookingService {

    private final SlotRepository slotRepository;
    private final SlotCounterRepository slotCounterRepository;
    private final Clock clock;

    public SlotBookingService(SlotRepository slotRepository, SlotCounterRepository slotCounterRepository, Clock clock) {
        this.slotRepository = slotRepository;
        this.slotCounterRepository = slotCounterRepository;
        this.clock = clock;
    }

    /**
     * Takes the next token of a session. A session that is still running (started but not ended) accepts
     * bookings.
     *
     * @param slotId the session
     * @return the reserved token number and the session's hospital and doctor
     * @throws ApiException 404 unknown session; 409 {@code SLOT_ENDED} when the session is over,
     *                      {@code SLOT_PAUSED} when the doctor paused it, {@code SLOT_FULL} when every token is
     *                      taken, or {@code SLOT_UNAVAILABLE} when the state changed during the request
     */
    @Transactional
    public TokenAllocation takeToken(UUID slotId) {
        Slot slot = slotRepository.findById(slotId).orElseThrow(() -> ApiException.notFound("Slot", slotId));

        if (hasEnded(slot)) {
            throw ApiException.conflict("SLOT_ENDED", "This session has already ended");
        }

        OptionalInt number = slotCounterRepository.takeNextToken(slotId);
        if (number.isPresent()) {
            return new TokenAllocation(slotId, slot.getHospitalId(), slot.getDoctorId(), number.getAsInt());
        }
        throw explainWhyNothingWasTaken(slotId);
    }

    private boolean hasEnded(Slot slot) {
        LocalDateTime end = LocalDateTime.of(slot.getSlotDate(), slot.getEndTime());
        return !end.isAfter(LocalDateTime.now(clock));
    }

    /** Reads the database state to give the caller the real reason, not a guess. */
    private ApiException explainWhyNothingWasTaken(UUID slotId) {
        SlotState state = slotCounterRepository.readState(slotId).orElse(null);
        if (state == null) {
            return ApiException.notFound("Slot", slotId);
        }
        if (SlotStatus.PAUSED.name().equals(state.status())) {
            return ApiException.conflict("SLOT_PAUSED", "Bookings are paused for this session");
        }
        if (state.bookedCount() >= state.maxTokens()) {
            return ApiException.conflict("SLOT_FULL", "All tokens for this session are booked");
        }
        return ApiException.conflict("SLOT_UNAVAILABLE", "The session is not available right now, please try again");
    }
}
