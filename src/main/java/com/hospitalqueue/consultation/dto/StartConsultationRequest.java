package com.hospitalqueue.consultation.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for starting a consultation.
 *
 * @param tokenId the token of the patient being seen; it must be IN_CONSULTATION (the doctor has called the patient)
 * @param notes   optional first notes, up to 10000 characters; blank counts as no notes
 */
public record StartConsultationRequest(
        @NotNull(message = "tokenId is required")
        UUID tokenId,

        @Size(max = 10000, message = "notes must be at most 10000 characters")
        String notes) {
}
