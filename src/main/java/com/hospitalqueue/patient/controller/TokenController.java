package com.hospitalqueue.patient.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hospitalqueue.patient.dto.TokenResponse;
import com.hospitalqueue.patient.service.TokenService;

/**
 * Token lookup endpoints. Tokens are created only by a paid checkout (see {@link CheckoutController} and
 * {@link PaymentCallbackController}); there is deliberately no way to book a token without paying the fee.
 * TEMPORARY: open until the authentication stage, when a patient will see only their own tokens.
 */
@RestController
@RequestMapping("/api")
public class TokenController {

    private final TokenService tokenService;

    public TokenController(TokenService tokenService) {
        this.tokenService = tokenService;
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
