package com.hospitalqueue.consultation.entity;

import java.time.Instant;
import java.util.UUID;

import com.hospitalqueue.common.security.EncryptedStringConverter;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * The doctor's consultation with one patient for one token. Maps to table {@code consultation}.
 *
 * <p>The doctor's notes are clinical data: they are encrypted before they reach the database and decrypted when
 * loaded (see {@link EncryptedStringConverter}), so a copy of the table alone reveals nothing. Hospital, patient, doctor
 * and token are plain ids, not JPA relationships, to keep module boundaries clean. Never log the notes.
 */
@Entity
@Table(name = "consultation")
public class Consultation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "hospital_id", nullable = false, updatable = false)
    private UUID hospitalId;

    @Column(name = "patient_id", nullable = false, updatable = false)
    private UUID patientId;

    @Column(name = "doctor_id", nullable = false, updatable = false)
    private UUID doctorId;

    @Column(name = "token_id", nullable = false, updatable = false)
    private UUID tokenId;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "ended_at", updatable = false)
    private Instant endedAt;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "notes", columnDefinition = "text")
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA; not for application use. */
    protected Consultation() {
    }

    /** Starts a consultation. {@code notes} may be null. */
    public Consultation(UUID hospitalId, UUID patientId, UUID doctorId, UUID tokenId, String notes) {
        this.hospitalId = hospitalId;
        this.patientId = patientId;
        this.doctorId = doctorId;
        this.tokenId = tokenId;
        this.notes = notes;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.startedAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    /** Replaces the doctor's notes; null clears them. */
    public void changeNotes(String newNotes) {
        this.notes = newNotes;
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

    public UUID getDoctorId() {
        return doctorId;
    }

    public UUID getTokenId() {
        return tokenId;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public String getNotes() {
        return notes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
