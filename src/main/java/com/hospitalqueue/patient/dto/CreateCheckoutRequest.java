package com.hospitalqueue.patient.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/**
 * Request body for starting a booking checkout.
 *
 * @param patientId the patient who will pay; in the authentication stage this comes from the login instead
 * @param slotId    the doctor session to book
 */
public record CreateCheckoutRequest(
        @NotNull(message = "patientId is required")
        UUID patientId,

        @NotNull(message = "slotId is required")
        UUID slotId) {
}
