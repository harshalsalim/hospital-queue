package com.hospitalqueue.patient.dto;

import java.time.Instant;
import java.util.UUID;

import com.hospitalqueue.billing.entity.FeeStatus;

/**
 * A booking checkout as returned by the API.
 *
 * @param checkoutId       checkout id; use it to follow the payment
 * @param patientId        the paying patient
 * @param slotId           the session being booked
 * @param amountPaise      convenience fee in paise (1000 paise = Rs 10)
 * @param status           PENDING until paid, then SUCCESS, FAILED or REFUNDED
 * @param gatewayReference the payment gateway's reference for this payment
 * @param holdExpiresAt    when the held token lapses if unpaid; null when the hold is gone or no longer needed
 * @param token            the booked token once payment succeeded, otherwise null
 */
public record CheckoutResponse(
        UUID checkoutId,
        UUID patientId,
        UUID slotId,
        int amountPaise,
        FeeStatus status,
        String gatewayReference,
        Instant holdExpiresAt,
        TokenResponse token) {
}
