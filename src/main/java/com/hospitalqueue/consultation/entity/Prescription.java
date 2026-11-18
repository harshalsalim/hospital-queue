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
import jakarta.persistence.Table;

/**
 * A prescription issued by a doctor at the end of a consultation. Maps to table {@code prescription}.
 *
 * <p>A prescription is a medical record, so it is <b>immutable</b>: every column is {@code updatable = false}, and the
 * database allows only one prescription per consultation. To correct a mistake, the doctor records a new consultation.
 * The doctor's council registration is copied onto the prescription when it is issued, so it stays correct even if the
 * doctor's profile changes later. The therapy advice and lab tests are encrypted at rest. Never log them.
 */
@Entity
@Table(name = "prescription")
public class Prescription {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "hospital_id", nullable = false, updatable = false)
    private UUID hospitalId;

    @Column(name = "patient_id", nullable = false, updatable = false)
    private UUID patientId;

    @Column(name = "doctor_id", nullable = false, updatable = false)
    private UUID doctorId;

    @Column(name = "consultation_id", nullable = false, updatable = false)
    private UUID consultationId;

    @Column(name = "doctor_registration_council", nullable = false, updatable = false, length = 100)
    private String doctorRegistrationCouncil;

    @Column(name = "doctor_registration_number", nullable = false, updatable = false, length = 50)
    private String doctorRegistrationNumber;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "therapy_advice", updatable = false, columnDefinition = "text")
    private String therapyAdvice;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "lab_tests", updatable = false, columnDefinition = "text")
    private String labTests;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, updatable = false)
    private Instant updatedAt;

    /** Required by JPA; not for application use. */
    protected Prescription() {
    }

    public Prescription(UUID hospitalId, UUID patientId, UUID doctorId, UUID consultationId,
                        String doctorRegistrationCouncil, String doctorRegistrationNumber,
                        String therapyAdvice, String labTests) {
        this.hospitalId = hospitalId;
        this.patientId = patientId;
        this.doctorId = doctorId;
        this.consultationId = consultationId;
        this.doctorRegistrationCouncil = doctorRegistrationCouncil;
        this.doctorRegistrationNumber = doctorRegistrationNumber;
        this.therapyAdvice = therapyAdvice;
        this.labTests = labTests;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
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

    public UUID getConsultationId() {
        return consultationId;
    }

    public String getDoctorRegistrationCouncil() {
        return doctorRegistrationCouncil;
    }

    public String getDoctorRegistrationNumber() {
        return doctorRegistrationNumber;
    }

    public String getTherapyAdvice() {
        return therapyAdvice;
    }

    public String getLabTests() {
        return labTests;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
