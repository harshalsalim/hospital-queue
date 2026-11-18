package com.hospitalqueue.consultation.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.hospitalqueue.consultation.dto.IssuePrescriptionRequest;
import com.hospitalqueue.consultation.dto.PrescriptionResponse;
import com.hospitalqueue.consultation.service.PrescriptionService;

import jakarta.validation.Valid;

/**
 * Prescription endpoints. TEMPORARY: open until the authentication stage, when only the treating doctor will be able to
 * issue a prescription, and only the patient, the prescribing doctor and the pharmacy the patient chose will be able to
 * read it, with every read written to the audit log.
 */
@RestController
@RequestMapping("/api")
public class PrescriptionController {

    private final PrescriptionService prescriptionService;

    public PrescriptionController(PrescriptionService prescriptionService) {
        this.prescriptionService = prescriptionService;
    }

    /**
     * Issues the prescription of a consultation. Returns 201. 400 for invalid input or an empty prescription; 404 unknown
     * consultation; 409 when a prescription was already issued (prescriptions cannot be changed).
     */
    @PostMapping("/consultations/{consultationId}/prescription")
    @ResponseStatus(HttpStatus.CREATED)
    public PrescriptionResponse issue(@PathVariable UUID consultationId,
                                      @Valid @RequestBody IssuePrescriptionRequest request) {
        return prescriptionService.issue(consultationId, request);
    }

    /** The prescription issued for a consultation; 404 when none was issued. */
    @GetMapping("/consultations/{consultationId}/prescription")
    public PrescriptionResponse getByConsultation(@PathVariable UUID consultationId) {
        return prescriptionService.getByConsultation(consultationId);
    }

    /** One prescription; 404 when not found, 400 when the id is not a UUID. */
    @GetMapping("/prescriptions/{id}")
    public PrescriptionResponse get(@PathVariable UUID id) {
        return prescriptionService.get(id);
    }

    /** A patient's 50 most recent prescriptions, newest first; 404 for an unknown patient. */
    @GetMapping("/patients/{patientId}/prescriptions")
    public List<PrescriptionResponse> listForPatient(@PathVariable UUID patientId) {
        return prescriptionService.listForPatient(patientId);
    }
}
