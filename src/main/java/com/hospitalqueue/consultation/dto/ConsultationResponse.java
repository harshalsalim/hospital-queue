package com.hospitalqueue.consultation.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * A consultation as returned by the API. The notes are the doctor's private clinical notes: in the authentication stage
 * they will be visible only to the treating doctor. Never log this object.
 *
 * @param id         consultation id
 * @param hospitalId hospital of the session
 * @param patientId  the patient
 * @param doctorId   the doctor of the session
 * @param tokenId    the patient's token
 * @param startedAt  when the consultation started (UTC)
 * @param endedAt    when it ended, null while it is open
 * @param notes      the doctor's notes, or null
 */
public record ConsultationResponse(
        UUID id,
        UUID hospitalId,
        UUID patientId,
        UUID doctorId,
        UUID tokenId,
        Instant startedAt,
        Instant endedAt,
        String notes) {
}
