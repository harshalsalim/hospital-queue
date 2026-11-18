package com.hospitalqueue.consultation.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A prescription as returned by the API. Health information: never log it. In the authentication stage it will be
 * visible only to the patient, the prescribing doctor and the pharmacy the patient chose.
 *
 * @param id                         prescription id
 * @param consultationId             the consultation it belongs to
 * @param hospitalId                 hospital where it was issued
 * @param patientId                  the patient
 * @param doctorId                   the prescribing doctor
 * @param doctorRegistrationCouncil  council that issued the doctor's registration, as it was when this was issued
 * @param doctorRegistrationNumber   the doctor's registration number, as it was when this was issued
 * @param therapyAdvice              therapy advice, or null
 * @param labTests                   lab tests, or null
 * @param items                      the medicines, in the order the doctor wrote them
 * @param createdAt                  when it was issued (UTC)
 */
public record PrescriptionResponse(
        UUID id,
        UUID consultationId,
        UUID hospitalId,
        UUID patientId,
        UUID doctorId,
        String doctorRegistrationCouncil,
        String doctorRegistrationNumber,
        String therapyAdvice,
        String labTests,
        List<PrescriptionItemResponse> items,
        Instant createdAt) {
}
