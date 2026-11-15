package com.hospitalqueue.billing.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hospitalqueue.billing.entity.FeeLedger;
import com.hospitalqueue.billing.entity.FeeStatus;
import com.hospitalqueue.billing.repository.FeeLedgerRepository;
import com.hospitalqueue.common.exception.ApiException;

/**
 * Business logic for the convenience-fee ledger: one row per booking checkout.
 *
 * <p>Other modules use the {@link FeeLedgerView} snapshot returned here and never touch the fee_ledger table
 * or entity directly. All status changes are atomic and conditional; the boolean they return says whether this call
 * was the one that changed the row.
 */
@Service
public class FeeLedgerService {

    /**
     * Read-only snapshot of a fee ledger row.
     *
     * @param id               checkout id (also the Redis hold id)
     * @param hospitalId       hospital the fee belongs to
     * @param patientId        patient who pays
     * @param slotId           session being booked
     * @param tokenId          token issued for this payment, null until the token exists
     * @param amountPaise      fee in paise (1000 paise = Rs 10)
     * @param status           payment state
     * @param gatewayReference payment gateway's reference for this payment
     * @param paidAt           when the payment succeeded, null until then
     * @param createdAt        when the checkout started
     */
    public record FeeLedgerView(UUID id, UUID hospitalId, UUID patientId, UUID slotId, UUID tokenId, int amountPaise,
                                FeeStatus status, String gatewayReference, Instant paidAt, Instant createdAt) {
    }

    private final FeeLedgerRepository feeLedgerRepository;
    private final Clock clock;

    public FeeLedgerService(FeeLedgerRepository feeLedgerRepository, Clock clock) {
        this.feeLedgerRepository = feeLedgerRepository;
        this.clock = clock;
    }

    /**
     * Records a new PENDING checkout fee. The gateway reference is a placeholder ({@code stub_...}) until a real
     * payment gateway is connected, which will return its own order id here.
     *
     * <p>The database allows only one PENDING checkout per patient and session; a second one fails with a
     * data-integrity error that the caller handles.
     *
     * @param id          checkout id chosen by the caller
     * @param hospitalId  hospital of the session
     * @param patientId   paying patient
     * @param slotId      session being booked
     * @param amountPaise fee in paise
     * @return the saved row
     */
    @Transactional
    public FeeLedgerView createPending(UUID id, UUID hospitalId, UUID patientId, UUID slotId, int amountPaise) {
        String gatewayReference = "stub_" + UUID.randomUUID();
        FeeLedger saved = feeLedgerRepository.saveAndFlush(
                new FeeLedger(id, hospitalId, patientId, slotId, amountPaise, gatewayReference));
        return toView(saved);
    }

    /**
     * Finds the patient's open (PENDING) checkout for a session, if any.
     *
     * @param patientId the patient
     * @param slotId    the session
     * @return the open checkout, or empty
     */
    @Transactional(readOnly = true)
    public Optional<FeeLedgerView> findPending(UUID patientId, UUID slotId) {
        return feeLedgerRepository
                .findFirstByPatientIdAndSlotIdAndPaymentStatusOrderByCreatedAtDesc(patientId, slotId, FeeStatus.PENDING)
                .map(this::toView);
    }

    /**
     * Finds one checkout.
     *
     * @param id checkout id
     * @return the checkout
     * @throws ApiException 404 when no checkout has this id
     */
    @Transactional(readOnly = true)
    public FeeLedgerView get(UUID id) {
        return feeLedgerRepository.findById(id).map(this::toView).orElseThrow(() -> ApiException.notFound("Checkout", id));
    }

    /**
     * Closes an unpaid checkout (for example when its hold expired). Does nothing when the checkout is no longer PENDING.
     *
     * @param id checkout id
     * @return true when it was PENDING and is now FAILED
     */
    @Transactional
    public boolean markFailed(UUID id) {
        return feeLedgerRepository.markFailed(id, Instant.now(clock)) == 1;
    }

    /**
     * Records that the payment was received.
     *
     * @param id checkout id
     * @return true when it was PENDING and is now SUCCESS; false when it was already changed by someone else
     */
    @Transactional
    public boolean markSuccess(UUID id) {
        return feeLedgerRepository.markSuccess(id, Instant.now(clock)) == 1;
    }

    /**
     * Links the issued token to a paid checkout.
     *
     * @param id      checkout id
     * @param tokenId the token issued for this payment
     * @return true when this call linked it; false when the checkout already has a token or is not paid
     * @throws org.springframework.dao.DataIntegrityViolationException when the token is already linked to another
     *                                                                 payment, which means this payment is a duplicate
     */
    @Transactional
    public boolean linkToken(UUID id, UUID tokenId) {
        return feeLedgerRepository.linkToken(id, tokenId, Instant.now(clock)) == 1;
    }

    /**
     * Marks a paid checkout as refunded because no token could be issued for it. In this stub stage this only records
     * the refund; the real gateway integration will also trigger the money transfer.
     *
     * @param id checkout id
     * @return true when it was paid without a token and is now REFUNDED
     */
    @Transactional
    public boolean markRefunded(UUID id) {
        return feeLedgerRepository.markRefunded(id, Instant.now(clock)) == 1;
    }

    private FeeLedgerView toView(FeeLedger ledger) {
        return new FeeLedgerView(ledger.getId(), ledger.getHospitalId(), ledger.getPatientId(), ledger.getSlotId(),
                ledger.getTokenId(), ledger.getAmountPaise(), ledger.getPaymentStatus(), ledger.getGatewayReference(),
                ledger.getPaidAt(), ledger.getCreatedAt());
    }
}
