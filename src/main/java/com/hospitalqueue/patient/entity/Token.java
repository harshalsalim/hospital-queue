package com.hospitalqueue.patient.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * A queue token booked by a patient for one session. Maps to table {@code token}.
 *
 * <p>The database enforces the rules that matter: the pair (slot, token number) is unique, and a patient can
 * hold only one active token per session (partial unique index). Patient, slot and hospital are plain ids,
 * not JPA relationships, to keep module boundaries clean and avoid N+1 queries.
 */
@Entity
@Table(name = "token")
public class Token {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "hospital_id", nullable = false, updatable = false)
    private UUID hospitalId;

    @Column(name = "patient_id", nullable = false, updatable = false)
    private UUID patientId;

    @Column(name = "slot_id", nullable = false, updatable = false)
    private UUID slotId;

    @Column(name = "token_number", nullable = false, updatable = false)
    private int tokenNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TokenStatus status;

    @Column(name = "booked_at", nullable = false, updatable = false)
    private Instant bookedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA; not for application use. */
    protected Token() {
    }

    /** Creates a freshly booked token. */
    public Token(UUID hospitalId, UUID patientId, UUID slotId, int tokenNumber) {
        this.hospitalId = hospitalId;
        this.patientId = patientId;
        this.slotId = slotId;
        this.tokenNumber = tokenNumber;
        this.status = TokenStatus.BOOKED;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.bookedAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
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

    public int getTokenNumber() {
        return tokenNumber;
    }

    public TokenStatus getStatus() {
        return status;
    }

    public Instant getBookedAt() {
        return bookedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
