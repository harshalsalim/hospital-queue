package com.hospitalqueue.patient.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for registering a patient.
 *
 * @param phoneNumber Indian mobile number in any common format, for example {@code 9876543210} or
 *                    {@code +91 98765 43210}; the format itself is checked by the service
 * @param name        optional display name, up to 255 characters; blank counts as no name
 */
public record RegisterPatientRequest(
        @NotBlank(message = "phoneNumber is required")
        @Size(max = 20, message = "phoneNumber must be at most 20 characters")
        String phoneNumber,

        @Size(max = 255, message = "name must be at most 255 characters")
        String name) {
}
