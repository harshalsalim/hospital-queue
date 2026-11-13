package com.hospitalqueue.doctor.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hospitalqueue.common.exception.ApiException;
import com.hospitalqueue.doctor.dto.CreateSlotRequest;
import com.hospitalqueue.doctor.dto.SlotResponse;
import com.hospitalqueue.doctor.entity.Doctor;
import com.hospitalqueue.doctor.entity.Slot;
import com.hospitalqueue.doctor.entity.SlotStatus;
import com.hospitalqueue.doctor.repository.DoctorRepository;
import com.hospitalqueue.doctor.repository.SlotRepository;

/**
 * Business logic for doctors' daily sessions: creating them, listing them, pausing or resuming them
 * and changing their token limit.
 *
 * <p>Slot data is not cached, because the booked count changes with every booking and must always be
 * exact. Dates and times follow the business clock (Asia/Kolkata).
 */
@Service
public class SlotService {

    /** How many days ahead a session can be created. */
    static final int MAX_DAYS_AHEAD = 90;

    private final SlotRepository slotRepository;
    private final DoctorRepository doctorRepository;
    private final Clock clock;

    public SlotService(SlotRepository slotRepository, DoctorRepository doctorRepository, Clock clock) {
        this.slotRepository = slotRepository;
        this.doctorRepository = doctorRepository;
        this.clock = clock;
    }

    /**
     * Creates a session for a doctor. Times are cut to whole minutes.
     *
     * <p>Rules: the end must be after the start; the session cannot already be over; it cannot be more
     * than {@value #MAX_DAYS_AHEAD} days ahead; and it cannot overlap another session of the same doctor
     * on the same day. The overlap rule is also enforced by the database (V5 migration), so two
     * simultaneous requests cannot both succeed.
     *
     * @param doctorId doctor who holds the session
     * @param request  validated request body
     * @return the created session
     * @throws ApiException 404 unknown doctor; 400 {@code INVALID_TIME_RANGE}, {@code SLOT_IN_PAST} or
     *                      {@code SLOT_TOO_FAR}; 409 {@code SLOT_OVERLAP}
     */
    @Transactional
    public SlotResponse create(UUID doctorId, CreateSlotRequest request) {
        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> ApiException.notFound("Doctor", doctorId));

        LocalDate date = request.date();
        LocalTime start = request.startTime().truncatedTo(ChronoUnit.MINUTES);
        LocalTime end = request.endTime().truncatedTo(ChronoUnit.MINUTES);

        if (!end.isAfter(start)) {
            throw ApiException.badRequest("INVALID_TIME_RANGE", "endTime must be after startTime");
        }

        LocalDate today = LocalDate.now(clock);
        LocalTime now = LocalTime.now(clock);
        if (date.isBefore(today) || (date.equals(today) && !end.isAfter(now))) {
            throw ApiException.badRequest("SLOT_IN_PAST", "The session has already ended");
        }
        if (date.isAfter(today.plusDays(MAX_DAYS_AHEAD))) {
            throw ApiException.badRequest("SLOT_TOO_FAR",
                    "Sessions can be created at most " + MAX_DAYS_AHEAD + " days ahead");
        }

        if (slotRepository.existsOverlapping(doctorId, date, start, end)) {
            throw ApiException.conflict("SLOT_OVERLAP", "The doctor already has an overlapping session on this date");
        }

        Slot saved = slotRepository.save(
                new Slot(doctor.getHospitalId(), doctorId, date, start, end, request.maxTokens()));
        return toResponse(saved);
    }

    /**
     * Lists a doctor's sessions for one day, earliest first.
     *
     * @param doctorId doctor id
     * @param date     the day, or {@code null} for today (hospital time zone)
     * @return the sessions, possibly empty
     * @throws ApiException 404 when the doctor does not exist
     */
    @Transactional(readOnly = true)
    public List<SlotResponse> list(UUID doctorId, LocalDate date) {
        if (!doctorRepository.existsById(doctorId)) {
            throw ApiException.notFound("Doctor", doctorId);
        }
        LocalDate day = date != null ? date : LocalDate.now(clock);
        return slotRepository.findByDoctorIdAndSlotDateOrderByStartTimeAsc(doctorId, day).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Pauses or resumes a session. Setting the status it already has is allowed and changes nothing.
     *
     * @param slotId slot id
     * @param status new status
     * @return the updated session
     * @throws ApiException 404 when the slot does not exist
     */
    @Transactional
    public SlotResponse updateStatus(UUID slotId, SlotStatus status) {
        int changed = slotRepository.updateStatus(slotId, status.name(), Instant.now(clock));
        if (changed == 0) {
            throw ApiException.notFound("Slot", slotId);
        }
        return reload(slotId);
    }

    /**
     * Changes a session's token limit. The new limit cannot be lower than the tokens already booked;
     * the check and the change are one atomic statement, so a booking arriving at the same moment is safe.
     *
     * @param slotId    slot id
     * @param maxTokens new limit
     * @return the updated session
     * @throws ApiException 404 when the slot does not exist; 409 {@code CAPACITY_BELOW_BOOKED} when the
     *                      limit would be lower than the tokens already booked
     */
    @Transactional
    public SlotResponse updateCapacity(UUID slotId, int maxTokens) {
        int changed = slotRepository.updateMaxTokens(slotId, maxTokens, Instant.now(clock));
        if (changed == 0) {
            if (!slotRepository.existsById(slotId)) {
                throw ApiException.notFound("Slot", slotId);
            }
            throw ApiException.conflict("CAPACITY_BELOW_BOOKED",
                    "The limit cannot be lower than the tokens already booked");
        }
        return reload(slotId);
    }

    private SlotResponse reload(UUID slotId) {
        Slot slot = slotRepository.findById(slotId).orElseThrow(() -> ApiException.notFound("Slot", slotId));
        return toResponse(slot);
    }

    private SlotResponse toResponse(Slot slot) {
        return new SlotResponse(slot.getId(), slot.getHospitalId(), slot.getDoctorId(), slot.getSlotDate(),
                slot.getStartTime(), slot.getEndTime(), slot.getMaxTokens(), slot.getBookedCount(),
                slot.getMaxTokens() - slot.getBookedCount(), slot.getStatus());
    }
}
