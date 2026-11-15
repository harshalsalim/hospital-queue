package com.hospitalqueue.patient.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of a payment callback, shaped like a payment gateway's webhook.
 *
 * @param status           {@code SUCCESS} or {@code FAILED}
 * @param gatewayReference the gateway's reference for the payment; must match the one returned when checkout started
 */
public record PaymentCallbackRequest(
        @NotNull(message = "status is required")
        PaymentOutcome status,

        @NotBlank(message = "gatewayReference is required")
        @Size(max = 255, message = "gatewayReference must be at most 255 characters")
        String gatewayReference) {
}
