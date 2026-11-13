package com.hospitalqueue.doctor.dto;

import com.hospitalqueue.doctor.entity.SlotStatus;

import jakarta.validation.constraints.NotNull;

/**
 * Request body for pausing or resuming a session.
 *
 * @param status {@code AVAILABLE} or {@code PAUSED}
 */
public record UpdateSlotStatusRequest(
        @NotNull(message = "status is required")
        SlotStatus status) {
}
