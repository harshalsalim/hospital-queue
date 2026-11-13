package com.hospitalqueue.patient.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hospitalqueue.patient.entity.Patient;

/** Data access for patients. The unique constraint on phone_number backs {@link #existsByPhoneNumber}. */
public interface PatientRepository extends JpaRepository<Patient, UUID> {

    /** True when a patient with this normalised phone number exists. */
    boolean existsByPhoneNumber(String phoneNumber);
}
