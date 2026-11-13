package com.hospitalqueue.doctor.dto;

import java.time.LocalDate;
import java.time.LocalTime;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for creating a doctor's session. Dates look like {@code 2026-11-12}, times like
 * {@code 09:00}. Seconds are ignored.
 *
 * @param date      day of the session (hospital time zone, Asia/Kolkata)
 * @param startTime start time
 * @param endTime   end time, must be after the start time
 * @param maxTokens daily token limit for this session, 1 to 500
 */
public record CreateSlotRequest(
        @NotNull(message = "date is required")
        LocalDate date,

        @NotNull(message = "startTime is required")
        LocalTime startTime,

        @NotNull(message = "endTime is required")
        LocalTime endTime,

        @NotNull(message = "maxTokens is required")
        @Min(value = 1, message = "maxTokens must be at least 1")
        @Max(value = 500, message = "maxTokens must be at most 500")
        Integer maxTokens) {
}
