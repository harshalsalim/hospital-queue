package com.hospitalqueue.patient.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hospitalqueue.patient.dto.BookTokenRequest;
import com.hospitalqueue.patient.dto.TokenResponse;
import com.hospitalqueue.patient.service.TokenService;
import com.hospitalqueue.patient.service.TokenService.BookingResult;

import jakarta.validation.Valid;

/**
 * Token endpoints. TEMPORARY: open until the authentication stage, when the patient will come from the login
 * and payment and the Redis slot hold will be added in front of booking.
 */
@RestController
@RequestMapping("/api")
public class TokenController {

    private final TokenService tokenService;

    public TokenController(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    /**
     * Books a token. Returns 201 for a new token, or 200 with the existing token when the patient already holds
     * an active token in this session (safe to retry). 404 for an unknown patient or session; 409 when the session
     * is full, paused or ended.
     */
    @PostMapping("/tokens")
    public ResponseEntity<TokenResponse> book(@Valid @RequestBody BookTokenRequest request) {
        BookingResult result = tokenService.book(request.patientId(), request.slotId());
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.token());
    }

    /** Returns one token; 404 when not found, 400 when the id is not a UUID. */
    @GetMapping("/tokens/{id}")
    public TokenResponse get(@PathVariable UUID id) {
        return tokenService.get(id);
    }

    /** Lists a patient's 50 most recent tokens, newest first; 404 for an unknown patient. */
    @GetMapping("/patients/{patientId}/tokens")
    public List<TokenResponse> listForPatient(@PathVariable UUID patientId) {
        return tokenService.listForPatient(patientId);
    }
}
