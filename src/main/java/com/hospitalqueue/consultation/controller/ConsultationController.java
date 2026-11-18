package com.hospitalqueue.consultation.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hospitalqueue.consultation.dto.ConsultationResponse;
import com.hospitalqueue.consultation.dto.StartConsultationRequest;
import com.hospitalqueue.consultation.dto.UpdateNotesRequest;
import com.hospitalqueue.consultation.service.ConsultationService;
import com.hospitalqueue.consultation.service.ConsultationService.StartResult;

import jakarta.validation.Valid;

/**
 * Consultation endpoints for the doctor's dashboard. TEMPORARY: open until the authentication stage, when only the
 * treating doctor will be able to start, read or change a consultation, and every read will be written to the audit log.
 */
@RestController
@RequestMapping("/api/consultations")
public class ConsultationController {

    private final ConsultationService consultationService;

    public ConsultationController(ConsultationService consultationService) {
        this.consultationService = consultationService;
    }

    /**
     * Starts the consultation of a called patient. Returns 201, or 200 with the existing consultation when the token
     * already has one (safe to retry). 404 unknown token; 409 when the patient has not been called.
     */
    @PostMapping
    public ResponseEntity<ConsultationResponse> start(@Valid @RequestBody StartConsultationRequest request) {
        StartResult result = consultationService.start(request.tokenId(), request.notes());
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.consultation());
    }

    /** Returns one consultation; 404 when not found, 400 when the id is not a UUID. */
    @GetMapping("/{id}")
    public ConsultationResponse get(@PathVariable UUID id) {
        return consultationService.get(id);
    }

    /** Replaces the doctor's notes (null or blank clears them). 404 when the consultation does not exist. */
    @PutMapping("/{id}/notes")
    public ConsultationResponse updateNotes(@PathVariable UUID id, @Valid @RequestBody UpdateNotesRequest request) {
        return consultationService.updateNotes(id, request.notes());
    }
}
