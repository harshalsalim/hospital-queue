package com.hospitalqueue.doctor.dto;

import java.util.UUID;

/**
 * The result of taking one token from a session: who the session belongs to and the token number
 * that was reserved. Returned by {@code SlotBookingService} to the booking code in the patient module.
 *
 * @param slotId      the session the token was taken from
 * @param hospitalId  hospital of the doctor
 * @param doctorId    doctor of the session
 * @param tokenNumber the reserved number, 1 for the first booking of the session, 2 for the next, and so on
 */
public record TokenAllocation(UUID slotId, UUID hospitalId, UUID doctorId, int tokenNumber) {
}
