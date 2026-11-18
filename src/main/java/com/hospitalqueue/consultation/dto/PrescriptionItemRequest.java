package com.hospitalqueue.consultation.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * One medicine the doctor prescribes.
 *
 * @param medicineName medicine name, 1 to 255 characters
 * @param dosage       how to take it, for example "1 tablet twice a day after food", 1 to 100 characters
 * @param durationDays how many days to take it, 1 to 365
 * @param quantity     how many units the pharmacy should supply, 1 to 1000
 */
public record PrescriptionItemRequest(
        @NotBlank(message = "medicineName is required")
        @Size(max = 255, message = "medicineName must be at most 255 characters")
        String medicineName,

        @NotBlank(message = "dosage is required")
        @Size(max = 100, message = "dosage must be at most 100 characters")
        String dosage,

        @NotNull(message = "durationDays is required")
        @Min(value = 1, message = "durationDays must be at least 1")
        @Max(value = 365, message = "durationDays must be at most 365")
        Integer durationDays,

        @NotNull(message = "quantity is required")
        @Min(value = 1, message = "quantity must be at least 1")
        @Max(value = 1000, message = "quantity must be at most 1000")
        Integer quantity) {
}
