package com.hospitalqueue.doctor.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import com.hospitalqueue.doctor.entity.SlotStatus;

/**
 * A doctor's session as returned by the API. Not cached, because the booked count changes with
 * every booking.
 *
 * @param id              slot id
 * @param hospitalId      hospital of the doctor
 * @param doctorId        doctor who holds the session
 * @param date            day of the session
 * @param startTime       start time
 * @param endTime         end time
 * @param maxTokens       daily token limit
 * @param bookedCount     tokens booked so far
 * @param remainingTokens tokens still available ({@code maxTokens - bookedCount})
 * @param status          AVAILABLE or PAUSED
 */
public record SlotResponse(
        UUID id,
        UUID hospitalId,
        UUID doctorId,
        LocalDate date,
        LocalTime startTime,
        LocalTime endTime,
        int maxTokens,
        int bookedCount,
        int remainingTokens,
        SlotStatus status) {
}
