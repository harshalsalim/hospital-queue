package com.hospitalqueue.consultation.dto;

import jakarta.validation.constraints.Size;

/**
 * Request body for replacing the doctor's notes.
 *
 * @param notes the new notes, up to 10000 characters; null or blank clears them
 */
public record UpdateNotesRequest(
        @Size(max = 10000, message = "notes must be at most 10000 characters")
        String notes) {
}
