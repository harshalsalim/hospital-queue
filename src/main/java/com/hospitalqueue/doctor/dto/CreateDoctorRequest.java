package com.hospitalqueue.doctor.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for adding a doctor to a hospital. The hospital comes from the URL.
 *
 * @param specialtyId         id of an existing specialty
 * @param name                doctor's full name, 1 to 255 characters
 * @param registrationCouncil medical council that issued the registration, 1 to 100 characters
 * @param registrationNumber  registration number, 1 to 50 characters; unique per council ignoring case
 */
public record CreateDoctorRequest(
        @NotNull(message = "specialtyId is required")
        UUID specialtyId,

        @NotBlank(message = "name is required")
        @Size(max = 255, message = "name must be at most 255 characters")
        String name,

        @NotBlank(message = "registrationCouncil is required")
        @Size(max = 100, message = "registrationCouncil must be at most 100 characters")
        String registrationCouncil,

        @NotBlank(message = "registrationNumber is required")
        @Size(max = 50, message = "registrationNumber must be at most 50 characters")
        String registrationNumber) {
}
