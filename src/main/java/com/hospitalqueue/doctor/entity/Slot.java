package com.hospitalqueue.doctor.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * A doctor's session on one day (for example 09:00 to 12:00) with a limit on how many tokens can be
 * booked. Maps to table {@code slot}.
 *
 * <p>The entity is effectively immutable after insert: every column is {@code updatable = false}.
 * Status, capacity and the booked count change only through the atomic UPDATE statements in
 * {@code SlotRepository}, so a stale in-memory copy can never overwrite {@code booked_count}
 * when bookings happen concurrently.
 */
@Entity
@Table(name = "slot")
public class Slot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "hospital_id", nullable = false, updatable = false)
    private UUID hospitalId;

    @Column(name = "doctor_id", nullable = false, updatable = false)
    private UUID doctorId;

    @Column(name = "date", nullable = false, updatable = false)
    private LocalDate slotDate;

    @Column(name = "start_time", nullable = false, updatable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false, updatable = false)
    private LocalTime endTime;

    @Column(name = "max_tokens", nullable = false, updatable = false)
    private int maxTokens;

    @Column(name = "booked_count", nullable = false, updatable = false)
    private int bookedCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, updatable = false, length = 20)
    private SlotStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, updatable = false)
    private Instant updatedAt;

    /** Required by JPA; not for application use. */
    protected Slot() {
    }

    /** Creates a new, open session with no tokens booked yet. */
    public Slot(UUID hospitalId, UUID doctorId, LocalDate slotDate, LocalTime startTime, LocalTime endTime, int maxTokens) {
        this.hospitalId = hospitalId;
        this.doctorId = doctorId;
        this.slotDate = slotDate;
        this.startTime = startTime;
        this.endTime = endTime;
        this.maxTokens = maxTokens;
        this.bookedCount = 0;
        this.status = SlotStatus.AVAILABLE;
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

    public UUID getDoctorId() {
        return doctorId;
    }

    public LocalDate getSlotDate() {
        return slotDate;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public int getBookedCount() {
        return bookedCount;
    }

    public SlotStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
