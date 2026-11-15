package com.hospitalqueue.patient.controller;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hospitalqueue.patient.dto.CheckoutResponse;
import com.hospitalqueue.patient.dto.PaymentCallbackRequest;
import com.hospitalqueue.patient.service.CheckoutService;

import jakarta.validation.Valid;

/**
 * DEVELOPMENT ONLY: a fake payment gateway callback. It exists only when {@code app.payment.stub-enabled=true},
 * because it lets anyone mark a checkout as paid. The real gateway webhook (with signature verification) replaces it,
 * and calls the same {@link CheckoutService#completePayment} method.
 */
@RestController
@RequestMapping("/api")
@ConditionalOnProperty(name = "app.payment.stub-enabled", havingValue = "true")
public class PaymentCallbackController {

    private static final Logger log = LoggerFactory.getLogger(PaymentCallbackController.class);

    private final CheckoutService checkoutService;

    public PaymentCallbackController(CheckoutService checkoutService) {
        this.checkoutService = checkoutService;
        log.warn("PAYMENT STUB IS ENABLED: anyone can mark a checkout as paid. Never enable it in production.");
    }

    /**
     * Applies a payment result. Returns 200 with the checkout (on SUCCESS it contains the token). Safe to send twice.
     * 400 for an invalid body or a gateway reference that does not match; 404 for an unknown checkout; 409 when the
     * checkout is closed, the payment was already completed, or no token could be issued (the payment is then
     * marked REFUNDED and the code says why).
     */
    @PostMapping("/checkouts/{checkoutId}/payment-callback")
    public CheckoutResponse paymentCallback(@PathVariable UUID checkoutId,
                                            @Valid @RequestBody PaymentCallbackRequest request) {
        return checkoutService.completePayment(checkoutId, request.status(), request.gatewayReference());
    }
}
