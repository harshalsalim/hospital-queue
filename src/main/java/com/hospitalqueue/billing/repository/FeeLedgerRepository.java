package com.hospitalqueue.billing.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.hospitalqueue.billing.entity.FeeLedger;
import com.hospitalqueue.billing.entity.FeeStatus;

/**
 * Data access for the fee ledger. Every status change is a single conditional UPDATE ("only if it is still in
 * this state"), so simultaneous payment callbacks cannot overwrite each other: exactly one of them changes the row.
 * Each method returns the number of rows changed (1 = this call did it, 0 = nothing to do).
 */
public interface FeeLedgerRepository extends JpaRepository<FeeLedger, UUID> {

    /** The patient's newest fee for this session with the given status. */
    Optional<FeeLedger> findFirstByPatientIdAndSlotIdAndPaymentStatusOrderByCreatedAtDesc(
            UUID patientId, UUID slotId, FeeStatus paymentStatus);

    /** PENDING to FAILED: closes an unpaid checkout. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE fee_ledger SET payment_status = 'FAILED', updated_at = :now "
            + "WHERE id = :id AND payment_status = 'PENDING'", nativeQuery = true)
    int markFailed(@Param("id") UUID id, @Param("now") Instant now);

    /** PENDING to SUCCESS: records that the payment was received, and when. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE fee_ledger SET payment_status = 'SUCCESS', paid_at = :now, updated_at = :now "
            + "WHERE id = :id AND payment_status = 'PENDING'", nativeQuery = true)
    int markSuccess(@Param("id") UUID id, @Param("now") Instant now);

    /**
     * Links the issued token to a paid fee, only if the fee is paid and has no token yet. The unique constraint on
     * token_id means a token can belong to only one payment: linking a token that another payment already owns fails
     * with a data-integrity error, which is how a duplicate payment is detected.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE fee_ledger SET token_id = :tokenId, updated_at = :now "
            + "WHERE id = :id AND payment_status = 'SUCCESS' AND token_id IS NULL", nativeQuery = true)
    int linkToken(@Param("id") UUID id, @Param("tokenId") UUID tokenId, @Param("now") Instant now);

    /** SUCCESS to REFUNDED, only while no token was issued for the payment. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE fee_ledger SET payment_status = 'REFUNDED', updated_at = :now "
            + "WHERE id = :id AND payment_status = 'SUCCESS' AND token_id IS NULL", nativeQuery = true)
    int markRefunded(@Param("id") UUID id, @Param("now") Instant now);
}
