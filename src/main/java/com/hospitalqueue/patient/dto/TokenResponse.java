package com.hospitalqueue.patient.dto;

import java.time.Instant;
import java.util.UUID;

import com.hospitalqueue.patient.entity.TokenStatus;

/**
 * A token as returned by the API.
 *
 * @param id          token id
 * @param hospitalId  hospital of the session
 * @param patientId   the patient who holds the token
 * @param slotId      the doctor session
 * @param tokenNumber the number the patient will be called by, starting at 1 for each session
 * @param status      current lifecycle status
 * @param bookedAt    booking time (UTC)
 */
public record TokenResponse(
        UUID id,
        UUID hospitalId,
        UUID patientId,
        UUID slotId,
        int tokenNumber,
        TokenStatus status,
        Instant bookedAt) {
}
