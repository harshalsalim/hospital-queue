package com.hospitalqueue.consultation.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hospitalqueue.consultation.entity.Prescription;

/** Data access for prescriptions. The unique constraint on consultation_id means one prescription per consultation. */
public interface PrescriptionRepository extends JpaRepository<Prescription, UUID> {

    /** The prescription issued for a consultation, if any. */
    Optional<Prescription> findByConsultationId(UUID consultationId);

    /** True when a prescription was already issued for the consultation. */
    boolean existsByConsultationId(UUID consultationId);

    /** The patient's 50 most recent prescriptions, newest first. */
    List<Prescription> findTop50ByPatientIdOrderByCreatedAtDesc(UUID patientId);
}
