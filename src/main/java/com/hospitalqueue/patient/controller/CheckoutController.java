package com.hospitalqueue.patient.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hospitalqueue.patient.dto.CheckoutResponse;
import com.hospitalqueue.patient.dto.CreateCheckoutRequest;
import com.hospitalqueue.patient.service.CheckoutService;
import com.hospitalqueue.patient.service.CheckoutService.StartResult;

import jakarta.validation.Valid;

/**
 * Checkout endpoints: the step before payment. TEMPORARY: open until the authentication stage, when the
 * patient will come from the login.
 */
@RestController
@RequestMapping("/api")
public class CheckoutController {

    private final CheckoutService checkoutService;

    public CheckoutController(CheckoutService checkoutService) {
        this.checkoutService = checkoutService;
    }

    /**
     * Starts a checkout: holds a token for the patient and records the fee to pay. Returns 201 for a new checkout, or
     * 200 with the patient's open checkout when one already exists (safe to retry). 404 for an unknown patient or
     * session; 409 when the patient already has a token, or the session is full, paused or ended; 503 when holds
     * cannot be taken right now.
     */
    @PostMapping("/checkouts")
    public ResponseEntity<CheckoutResponse> start(@Valid @RequestBody CreateCheckoutRequest request) {
        StartResult result = checkoutService.start(request.patientId(), request.slotId());
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.checkout());
    }

    /** Returns the state of a checkout; 404 when not found, 400 when the id is not a UUID. */
    @GetMapping("/checkouts/{id}")
    public CheckoutResponse get(@PathVariable UUID id) {
        return checkoutService.get(id);
    }
}
