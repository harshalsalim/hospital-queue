package com.hospitalqueue.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for creating a specialty.
 *
 * @param name specialty name, 1 to 100 characters; unique ignoring case
 */
public record CreateSpecialtyRequest(
        @NotBlank(message = "name is required")
        @Size(max = 100, message = "name must be at most 100 characters")
        String name) {
}
