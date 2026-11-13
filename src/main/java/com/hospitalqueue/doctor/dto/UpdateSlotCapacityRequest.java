package com.hospitalqueue.doctor.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for changing a session's daily token limit.
 *
 * @param maxTokens new limit, 1 to 500; cannot be lower than the tokens already booked
 */
public record UpdateSlotCapacityRequest(
        @NotNull(message = "maxTokens is required")
        @Min(value = 1, message = "maxTokens must be at least 1")
        @Max(value = 500, message = "maxTokens must be at most 500")
        Integer maxTokens) {
}
