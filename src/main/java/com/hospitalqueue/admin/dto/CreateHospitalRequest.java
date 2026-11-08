package com.hospitalqueue.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for creating a hospital.
 *
 * @param name    hospital name, 1 to 255 characters
 * @param address postal address, 1 to 1000 characters
 */
public record CreateHospitalRequest(
        @NotBlank(message = "name is required")
        @Size(max = 255, message = "name must be at most 255 characters")
        String name,

        @NotBlank(message = "address is required")
        @Size(max = 1000, message = "address must be at most 1000 characters")
        String address) {
}
