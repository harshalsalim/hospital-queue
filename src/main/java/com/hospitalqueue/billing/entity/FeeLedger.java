package com.hospitalqueue.billing.entity;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Persistable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/**
 * One row per booking checkout: the convenience fee a patient pays for a token. Maps to table
 * {@code fee_ledger}. The row exists from the moment checkout starts (status PENDING), before the token does,
 * and its id doubles as the checkout id and the Redis hold id.
 *
 * <p>The id is chosen by the application, so the entity implements {@link Persistable} to make Spring Data
 * INSERT directly instead of doing a SELECT first. Every column is {@code updatable = false}: status changes
 * happen only through atomic conditional UPDATE statements in the repository, so two simultaneous payment
 * callbacks can never both win.
 */
@Entity
@Table(name = "fee_ledger")
public class FeeLedger implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "hospital_id", nullable = false, updatable = false)
    private UUID hospitalId;

    @Column(name = "patient_id", nullable = false, updatable = false)
    private UUID patientId;

    @Column(name = "slot_id", nullable = false, updatable = false)
    private UUID slotId;

    @Column(name = "token_id", updatable = false)
    private UUID tokenId;

    @Column(name = "amount_paise", nullable = false, updatable = false)
    private int amountPaise;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, updatable = false, length = 20)
    private FeeStatus paymentStatus;

    @Column(name = "gateway_reference", nullable = false, updatable = false, length = 255)
    private String gatewayReference;

    @Column(name = "paid_at", updatable = false)
    private Instant paidAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, updatable = false)
    private Instant updatedAt;

    @Transient
    private boolean newEntity = true;

    /** Required by JPA; not for application use. */
    protected FeeLedger() {
    }

    /** Creates a new PENDING checkout fee. */
    public FeeLedger(UUID id, UUID hospitalId, UUID patientId, UUID slotId, int amountPaise, String gatewayReference) {
        this.id = id;
        this.hospitalId = hospitalId;
        this.patientId = patientId;
        this.slotId = slotId;
        this.amountPaise = amountPaise;
        this.gatewayReference = gatewayReference;
        this.paymentStatus = FeeStatus.PENDING;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.newEntity = false;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    public UUID getHospitalId() {
        return hospitalId;
    }

    public UUID getPatientId() {
        return patientId;
    }

    public UUID getSlotId() {
        return slotId;
    }

    public UUID getTokenId() {
        return tokenId;
    }

    public int getAmountPaise() {
        return amountPaise;
    }

    public FeeStatus getPaymentStatus() {
        return paymentStatus;
    }

    public String getGatewayReference() {
        return gatewayReference;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
