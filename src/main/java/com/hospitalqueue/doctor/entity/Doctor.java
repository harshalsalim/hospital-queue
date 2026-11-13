package com.hospitalqueue.doctor.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * A doctor who works at one hospital and belongs to one specialty.
 * Maps to table {@code doctor}.
 *
 * <p>{@code hospitalId} and {@code specialtyId} are plain ids, not JPA relationships. They belong
 * to the admin module, and this keeps module boundaries clean and rules out N+1 query problems.
 * The link to a login account ({@code app_user_id}) is added in the authentication stage.
 */
@Entity
@Table(name = "doctor")
public class Doctor {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "hospital_id", nullable = false, updatable = false)
    private UUID hospitalId;

    @Column(name = "specialty_id", nullable = false)
    private UUID specialtyId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "registration_council", nullable = false, length = 100)
    private String registrationCouncil;

    @Column(name = "registration_number", nullable = false, length = 50)
    private String registrationNumber;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA; not for application use. */
    protected Doctor() {
    }

    public Doctor(UUID hospitalId, UUID specialtyId, String name, String registrationCouncil, String registrationNumber) {
        this.hospitalId = hospitalId;
        this.specialtyId = specialtyId;
        this.name = name;
        this.registrationCouncil = registrationCouncil;
        this.registrationNumber = registrationNumber;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
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

    public UUID getSpecialtyId() {
        return specialtyId;
    }

    public String getName() {
        return name;
    }

    public String getRegistrationCouncil() {
        return registrationCouncil;
    }

    public String getRegistrationNumber() {
        return registrationNumber;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
