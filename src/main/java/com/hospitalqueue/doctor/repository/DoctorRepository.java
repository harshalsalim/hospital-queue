package com.hospitalqueue.doctor.repository;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.hospitalqueue.doctor.entity.Doctor;

/**
 * Data access for doctors. The filtered queries use the indexes on hospital_id and specialty_id
 * created in V1. Unfiltered paging comes from {@code findAll(Pageable)}.
 */
public interface DoctorRepository extends JpaRepository<Doctor, UUID> {

    Page<Doctor> findByHospitalId(UUID hospitalId, Pageable pageable);

    Page<Doctor> findBySpecialtyId(UUID specialtyId, Pageable pageable);

    Page<Doctor> findByHospitalIdAndSpecialtyId(UUID hospitalId, UUID specialtyId, Pageable pageable);

    /** True when this council registration is already taken, ignoring upper/lower case. */
    boolean existsByRegistrationCouncilIgnoreCaseAndRegistrationNumberIgnoreCase(
            String registrationCouncil, String registrationNumber);
}
