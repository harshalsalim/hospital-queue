package com.hospitalqueue.patient.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * The live queue of one session, for the waiting-room display and for patients.
 *
 * @param slotId                     the session
 * @param nowServingTokenNumber      the token being served now, or null when nobody is in consultation
 * @param nextWaitingTokenNumber     the token that will be called next (lowest waiting number), or null when nobody waits
 * @param waitingCount               patients who have arrived and wait
 * @param bookedNotArrivedCount      tokens booked but whose patient has not arrived yet
 * @param completedCount             consultations finished
 * @param noShowCount                patients who did not come
 * @param cancelledCount             cancelled tokens
 * @param averageConsultationMinutes the average used for waiting-time estimates
 * @param asOf                       when this was read; it can be a few seconds old
 */
public record QueueStatusResponse(
        UUID slotId,
        Integer nowServingTokenNumber,
        Integer nextWaitingTokenNumber,
        int waitingCount,
        int bookedNotArrivedCount,
        int completedCount,
        int noShowCount,
        int cancelledCount,
        int averageConsultationMinutes,
        Instant asOf) {
}
