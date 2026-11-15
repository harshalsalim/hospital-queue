package com.hospitalqueue.patient.service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.hospitalqueue.billing.entity.FeeStatus;
import com.hospitalqueue.billing.service.FeeLedgerService;
import com.hospitalqueue.billing.service.FeeLedgerService.FeeLedgerView;
import com.hospitalqueue.common.exception.ApiException;
import com.hospitalqueue.doctor.service.SlotHoldService;
import com.hospitalqueue.doctor.service.SlotHoldService.SlotHold;
import com.hospitalqueue.patient.dto.CheckoutResponse;
import com.hospitalqueue.patient.dto.PaymentOutcome;
import com.hospitalqueue.patient.dto.TokenResponse;
import com.hospitalqueue.patient.service.TokenService.BookingResult;

/**
 * The booking checkout: start it (hold a token, record the fee), then complete it when the payment result arrives.
 *
 * <p>Starting a checkout holds a token in Redis for a limited time and records the PENDING convenience fee. Order
 * matters: the hold comes first (so a full session never creates a fee), then the fee row; if saving the fee fails,
 * the hold is given back.
 *
 * <p>Completing a checkout is built so that a payment callback can safely arrive twice, or at the same time as
 * itself, or after a crash halfway:
 * <ul>
 *   <li>The state moves only through atomic conditional updates (PENDING to SUCCESS to token linked).</li>
 *   <li>The token is issued by {@link TokenService#book}, which is itself repeat-safe.</li>
 *   <li>A token can be linked to only one payment (database constraint). Linking a token that another payment already
 *       owns is how a duplicate payment is detected, and it is refunded.</li>
 *   <li>If the payment arrived but no token can be issued (session full, paused or ended), the payment is marked
 *       REFUNDED and the caller gets the reason. A paid checkout with no token yet (for example after a crash)
 *       is finished by the next callback.</li>
 * </ul>
 *
 * <p>This service is deliberately not transactional: the hold lives in Redis, and each database step commits on its
 * own so that every step is visible to concurrent callbacks.
 *
 * <p>Known limit of the stub stage: if a checkout expires unpaid and a late SUCCESS callback arrives for it, the
 * callback is refused with CHECKOUT_CLOSED; with a real gateway that money must be refunded automatically.
 */
@Service
public class CheckoutService {

    /**
     * Outcome of starting a checkout.
     *
     * @param checkout the checkout
     * @param created  true when this request created it, false when the patient already had an open checkout
     */
    public record StartResult(CheckoutResponse checkout, boolean created) {
    }

    private final PatientService patientService;
    private final TokenService tokenService;
    private final SlotHoldService slotHoldService;
    private final FeeLedgerService feeLedgerService;
    private final int convenienceFeePaise;

    public CheckoutService(PatientService patientService,
                           TokenService tokenService,
                           SlotHoldService slotHoldService,
                           FeeLedgerService feeLedgerService,
                           @Value("${app.booking.convenience-fee-paise:1000}") int convenienceFeePaise) {
        this.patientService = patientService;
        this.tokenService = tokenService;
        this.slotHoldService = slotHoldService;
        this.feeLedgerService = feeLedgerService;
        this.convenienceFeePaise = convenienceFeePaise;
    }

    /**
     * Starts a checkout for a patient and session, or returns the patient's open checkout when one already exists
     * with a live hold (safe to retry).
     *
     * @param patientId the patient
     * @param slotId    the session
     * @return the checkout and whether it was newly created
     * @throws ApiException 404 unknown patient or session; 409 {@code TOKEN_ALREADY_BOOKED}, {@code SLOT_ENDED},
     *                      {@code SLOT_PAUSED}, {@code SLOT_FULL} or {@code SLOT_TEMPORARILY_FULL}; 503
     *                      {@code HOLD_UNAVAILABLE} when holds cannot be taken right now
     */
    public StartResult start(UUID patientId, UUID slotId) {
        if (!patientService.exists(patientId)) {
            throw ApiException.notFound("Patient", patientId);
        }
        if (tokenService.hasActiveToken(patientId, slotId)) {
            throw ApiException.conflict("TOKEN_ALREADY_BOOKED", "The patient already has a token in this session");
        }

        Optional<FeeLedgerView> open = feeLedgerService.findPending(patientId, slotId);
        if (open.isPresent()) {
            Optional<Instant> expiry = slotHoldService.expiryOf(slotId, open.get().id());
            if (expiry.isPresent()) {
                return new StartResult(toResponse(open.get(), expiry.get(), null), false);
            }
            // The old checkout's hold lapsed without payment: close it so a new one can start.
            feeLedgerService.markFailed(open.get().id());
        }

        UUID checkoutId = UUID.randomUUID();
        SlotHold hold = slotHoldService.acquire(slotId, checkoutId);
        try {
            FeeLedgerView ledger = feeLedgerService.createPending(
                    checkoutId, hold.hospitalId(), patientId, slotId, convenienceFeePaise);
            return new StartResult(toResponse(ledger, hold.expiresAt(), null), true);
        } catch (DataIntegrityViolationException lostTheRace) {
            // A simultaneous request from the same patient opened the checkout first: hand back theirs.
            slotHoldService.release(slotId, checkoutId);
            FeeLedgerView winner = feeLedgerService.findPending(patientId, slotId).orElseThrow(() -> lostTheRace);
            Instant expiry = slotHoldService.expiryOf(slotId, winner.id()).orElseThrow(() -> lostTheRace);
            return new StartResult(toResponse(winner, expiry, null), false);
        } catch (RuntimeException failure) {
            slotHoldService.release(slotId, checkoutId);
            throw failure;
        }
    }

    /**
     * Reports the state of a checkout, including the token once payment has succeeded.
     *
     * @param checkoutId the checkout id
     * @return the checkout
     * @throws ApiException 404 when no checkout has this id
     */
    public CheckoutResponse get(UUID checkoutId) {
        FeeLedgerView ledger = feeLedgerService.get(checkoutId);

        Instant holdExpiresAt = null;
        if (ledger.status() == FeeStatus.PENDING) {
            try {
                holdExpiresAt = slotHoldService.expiryOf(ledger.slotId(), checkoutId).orElse(null);
            } catch (ApiException redisUnavailable) {
                // Reporting status must still work when Redis is down; the expiry is simply unknown.
                holdExpiresAt = null;
            }
        }
        TokenResponse token = ledger.tokenId() != null ? tokenService.get(ledger.tokenId()) : null;
        return toResponse(ledger, holdExpiresAt, token);
    }

    /**
     * Applies a payment result to a checkout. Safe to call repeatedly and concurrently with the same result.
     *
     * @param checkoutId       the checkout
     * @param outcome          what the gateway reported
     * @param gatewayReference the gateway's reference; must match the checkout's
     * @return the checkout after the result was applied; on SUCCESS it contains the booked token
     * @throws ApiException 404 unknown checkout; 400 {@code GATEWAY_REFERENCE_MISMATCH}; 409 {@code CHECKOUT_CLOSED}
     *                      (a SUCCESS for a checkout that was already closed), {@code PAYMENT_ALREADY_COMPLETED} (a
     *                      FAILED for a checkout already paid), or, when the payment arrived but no token could be
     *                      issued, the reason ({@code SLOT_FULL}, {@code SLOT_PAUSED}, {@code SLOT_ENDED},
     *                      {@code TOKEN_ALREADY_BOOKED}) with the payment marked REFUNDED
     */
    public CheckoutResponse completePayment(UUID checkoutId, PaymentOutcome outcome, String gatewayReference) {
        FeeLedgerView ledger = feeLedgerService.get(checkoutId);
        if (!ledger.gatewayReference().equals(gatewayReference)) {
            throw ApiException.badRequest("GATEWAY_REFERENCE_MISMATCH", "The gateway reference does not belong to this checkout");
        }
        return outcome == PaymentOutcome.SUCCESS ? paymentSucceeded(ledger) : paymentFailed(ledger);
    }

    private CheckoutResponse paymentSucceeded(FeeLedgerView first) {
        FeeLedgerView ledger = first;
        if (ledger.status() == FeeStatus.PENDING) {
            feeLedgerService.markSuccess(ledger.id());
            ledger = feeLedgerService.get(ledger.id());
        }
        if (ledger.status() != FeeStatus.SUCCESS) {
            throw ApiException.conflict("CHECKOUT_CLOSED",
                    "This checkout is closed, so the payment cannot be used for a token. It will be refunded.");
        }
        if (ledger.tokenId() != null) {
            return get(ledger.id());
        }
        return issueToken(ledger);
    }

    private CheckoutResponse issueToken(FeeLedgerView ledger) {
        BookingResult booked;
        try {
            booked = tokenService.book(ledger.patientId(), ledger.slotId());
        } catch (ApiException rejected) {
            throw refund(ledger, rejected.getStatus(), rejected.getCode(), rejected.getMessage());
        }

        UUID tokenId = booked.token().id();
        try {
            if (!feeLedgerService.linkToken(ledger.id(), tokenId)) {
                // Someone else (for example the same callback arriving twice) linked it first. That is fine when
                // they linked the same token.
                FeeLedgerView after = feeLedgerService.get(ledger.id());
                if (!tokenId.equals(after.tokenId())) {
                    throw ApiException.conflict("CHECKOUT_CLOSED",
                            "This checkout is closed, so the payment cannot be used for a token. It will be refunded.");
                }
            }
        } catch (DataIntegrityViolationException tokenBelongsToAnotherPayment) {
            throw refund(ledger, HttpStatus.CONFLICT, "TOKEN_ALREADY_BOOKED",
                    "The patient already has a token in this session");
        }

        slotHoldService.release(ledger.slotId(), ledger.id());
        return get(ledger.id());
    }

    private CheckoutResponse paymentFailed(FeeLedgerView ledger) {
        if (ledger.status() == FeeStatus.SUCCESS || ledger.status() == FeeStatus.REFUNDED) {
            throw ApiException.conflict("PAYMENT_ALREADY_COMPLETED", "This payment was already completed");
        }
        if (ledger.status() == FeeStatus.PENDING) {
            feeLedgerService.markFailed(ledger.id());
            FeeLedgerView after = feeLedgerService.get(ledger.id());
            if (after.status() == FeeStatus.SUCCESS || after.status() == FeeStatus.REFUNDED) {
                throw ApiException.conflict("PAYMENT_ALREADY_COMPLETED", "This payment was already completed");
            }
            slotHoldService.release(ledger.slotId(), ledger.id());
        }
        return get(ledger.id());
    }

    /** Records the refund, gives the hold back, and builds the error that tells the caller why no token was issued. */
    private ApiException refund(FeeLedgerView ledger, HttpStatus status, String code, String reason) {
        feeLedgerService.markRefunded(ledger.id());
        slotHoldService.release(ledger.slotId(), ledger.id());
        String sentence = reason.endsWith(".") ? reason : reason + ".";
        return new ApiException(status, code, sentence + " The payment will be refunded.");
    }

    private CheckoutResponse toResponse(FeeLedgerView ledger, Instant holdExpiresAt, TokenResponse token) {
        return new CheckoutResponse(ledger.id(), ledger.patientId(), ledger.slotId(), ledger.amountPaise(),
                ledger.status(), ledger.gatewayReference(), holdExpiresAt, token);
    }
}
