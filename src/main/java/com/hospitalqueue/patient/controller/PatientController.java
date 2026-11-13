package com.hospitalqueue.patient.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.hospitalqueue.patient.dto.PatientResponse;
import com.hospitalqueue.patient.dto.RegisterPatientRequest;
import com.hospitalqueue.patient.service.PatientService;

import jakarta.validation.Valid;

/**
 * Patient endpoints.
 *
 * <p>TEMPORARY: these are open until the authentication stage. Then registration will require a verified
 * phone number (OTP), and reading a patient will be allowed only for that patient and, with an audit log
 * entry, for the treating doctor.
 */
@RestController
@RequestMapping("/api/patients")
public class PatientController {

    private final PatientService patientService;

    public PatientController(PatientService patientService) {
        this.patientService = patientService;
    }

    /** Registers a patient. Returns 201; 400 for invalid input; 409 when the number is already registered. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PatientResponse register(@Valid @RequestBody RegisterPatientRequest request) {
        return patientService.register(request);
    }

    /** Returns one patient with the phone number masked; 404 when not found, 400 when the id is not a UUID. */
    @GetMapping("/{id}")
    public PatientResponse get(@PathVariable UUID id) {
        return patientService.get(id);
    }
}
