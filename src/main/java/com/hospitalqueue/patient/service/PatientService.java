
package com.hospitalqueue.patient.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hospitalqueue.common.exception.ApiException;
import com.hospitalqueue.common.util.PhoneNumbers;
import com.hospitalqueue.patient.dto.PatientResponse;
import com.hospitalqueue.patient.dto.RegisterPatientRequest;
import com.hospitalqueue.patient.entity.Patient;
import com.hospitalqueue.patient.repository.PatientRepository;

/**
 * Business logic for patients.
 *
 * <p>Patient data is personal data, so nothing here is cached, and no phone number or name is ever put
 * in a log line or an error message. In the authentication stage, registration will happen only after
 * the phone number is verified by a one-time password.
 */
@Service
public class PatientService {

    private final PatientRepository patientRepository;

    public PatientService(PatientRepository patientRepository) {
        this.patientRepository = patientRepository;
    }

    /**
     * Registers a patient. The phone number is normalised to {@code +91XXXXXXXXXX}, so different
     * spellings of the same number count as the same person. The unique index on phone_number
     * guarantees there is never a duplicate, even for simultaneous requests.
     *
     * @param request validated request body
     * @return the new patient, with the phone number masked
     * @throws ApiException 400 {@code INVALID_PHONE_NUMBER} for a number that is not a valid Indian mobile;
     *                      409 {@code PATIENT_EXISTS} when the number is already registered
     */
    @Transactional
    public PatientResponse register(RegisterPatientRequest request) {
        String phone = PhoneNumbers.normalize(request.phoneNumber())
                .orElseThrow(() -> ApiException.badRequest("INVALID_PHONE_NUMBER",
                        "Enter a valid 10-digit Indian mobile number"));

        if (patientRepository.existsByPhoneNumber(phone)) {
            throw ApiException.conflict("PATIENT_EXISTS", "A patient with this phone number is already registered");
        }

        Patient saved = patientRepository.save(new Patient(phone, blankToNull(request.name())));
        return toResponse(saved);
    }

    /**
     * Finds one patient.
     *
     * @param id patient id
     * @return the patient, with the phone number masked
     * @throws ApiException 404 when no patient has this id
     */
    @Transactional(readOnly = true)
    public PatientResponse get(UUID id) {
        Patient patient = patientRepository.findById(id).orElseThrow(() -> ApiException.notFound("Patient", id));
        return toResponse(patient);
    }

    /**
     * Cheap existence check for other modules, for example before booking a token.
     *
     * @param id patient id
     * @return true when the patient exists
     */
    @Transactional(readOnly = true)
    public boolean exists(UUID id) {
        return patientRepository.existsById(id);
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private PatientResponse toResponse(Patient patient) {
        return new PatientResponse(patient.getId(), PhoneNumbers.mask(patient.getPhoneNumber()),
                patient.getName(), patient.getCreatedAt());
    }
}
