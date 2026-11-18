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
 * One medicine line of a prescription. Maps to table {@code prescription_item}. Immutable, like the prescription it
 * belongs to. The medicine name and the dosage are health information, so they are encrypted at rest. Never log them.
 * {@code sortOrder} keeps the medicines in the order the doctor wrote them (0 for the first).
 */
@Entity
@Table(name = "prescription_item")
public class PrescriptionItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "prescription_id", nullable = false, updatable = false)
    private UUID prescriptionId;

    @Column(name = "sort_order", nullable = false, updatable = false)
    private int sortOrder;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "medicine_name", nullable = false, updatable = false, columnDefinition = "text")
    private String medicineName;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "dosage", nullable = false, updatable = false, columnDefinition = "text")
    private String dosage;

    @Column(name = "duration_days", nullable = false, updatable = false)
    private int durationDays;

    @Column(name = "quantity", nullable = false, updatable = false)
    private int quantity;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Required by JPA; not for application use. */
    protected PrescriptionItem() {
    }

    public PrescriptionItem(UUID prescriptionId, int sortOrder, String medicineName, String dosage,
                            int durationDays, int quantity) {
        this.prescriptionId = prescriptionId;
        this.sortOrder = sortOrder;
        this.medicineName = medicineName;
        this.dosage = dosage;
        this.durationDays = durationDays;
        this.quantity = quantity;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getPrescriptionId() {
        return prescriptionId;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public String getMedicineName() {
        return medicineName;
    }

    public String getDosage() {
        return dosage;
    }

    public int getDurationDays() {
        return durationDays;
    }

    public int getQuantity() {
        return quantity;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
