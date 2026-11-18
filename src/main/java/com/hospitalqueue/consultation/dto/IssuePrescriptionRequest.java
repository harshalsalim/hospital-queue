package com.hospitalqueue.consultation.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

/**
 * Request body for issuing a prescription. At least one of the three parts must be present: a medicine, therapy
 * advice, or lab tests.
 *
 * @param therapyAdvice advice such as physiotherapy or diet, up to 4000 characters; blank counts as none
 * @param labTests      lab tests to take, up to 2000 characters; blank counts as none
 * @param items         the medicines, up to 20, in the order they should be shown; may be empty or omitted
 */
public record IssuePrescriptionRequest(
        @Size(max = 4000, message = "therapyAdvice must be at most 4000 characters")
        String therapyAdvice,

        @Size(max = 2000, message = "labTests must be at most 2000 characters")
        String labTests,

        @Valid
        @Size(max = 20, message = "a prescription can have at most 20 medicines")
        List<PrescriptionItemRequest> items) {
}
