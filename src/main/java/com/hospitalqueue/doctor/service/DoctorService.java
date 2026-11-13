package com.hospitalqueue.doctor.service;

import java.util.UUID;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hospitalqueue.admin.service.HospitalService;
import com.hospitalqueue.admin.service.SpecialtyService;
import com.hospitalqueue.common.config.CacheNames;
import com.hospitalqueue.common.dto.PageResponse;
import com.hospitalqueue.common.exception.ApiException;
import com.hospitalqueue.doctor.dto.CreateDoctorRequest;
import com.hospitalqueue.doctor.dto.DoctorResponse;
import com.hospitalqueue.doctor.entity.Doctor;
import com.hospitalqueue.doctor.repository.DoctorRepository;

/**
 * Business logic for doctors: onboarding, lookup and discovery by hospital and specialty.
 *
 * <p>Hospitals and specialties belong to the admin module, so they are checked through
 * {@link HospitalService} and {@link SpecialtyService}, never through their tables.
 * Reads are cached in Redis; adding a doctor evicts the cache after the transaction commits.
 */
@Service
public class DoctorService {

    private final DoctorRepository doctorRepository;
    private final HospitalService hospitalService;
    private final SpecialtyService specialtyService;

    public DoctorService(DoctorRepository doctorRepository,
                         HospitalService hospitalService,
                         SpecialtyService specialtyService) {
        this.doctorRepository = doctorRepository;
        this.hospitalService = hospitalService;
        this.specialtyService = specialtyService;
    }

    /**
     * Adds a doctor to a hospital. Text fields are trimmed. The registration (council + number)
     * must be unique ignoring case: the check here gives a clean 409, and the unique index
     * {@code uq_doctor_registration_lower} (V3 migration) guarantees it under concurrent requests.
     *
     * @param hospitalId hospital the doctor will work at
     * @param request    validated request body
     * @return the saved doctor
     * @throws ApiException 404 when the hospital does not exist, 400 {@code INVALID_SPECIALTY} when the
     *                      specialty does not exist, 409 {@code DOCTOR_REGISTRATION_EXISTS} for a duplicate
     */
    @Transactional
    @CacheEvict(cacheNames = CacheNames.DOCTORS, allEntries = true)
    public DoctorResponse create(UUID hospitalId, CreateDoctorRequest request) {
        if (!hospitalService.exists(hospitalId)) {
            throw ApiException.notFound("Hospital", hospitalId);
        }
        if (!specialtyService.exists(request.specialtyId())) {
            throw ApiException.badRequest("INVALID_SPECIALTY", "Unknown specialty: " + request.specialtyId());
        }

        String council = request.registrationCouncil().trim();
        String number = request.registrationNumber().trim();
        if (doctorRepository.existsByRegistrationCouncilIgnoreCaseAndRegistrationNumberIgnoreCase(council, number)) {
            throw ApiException.conflict("DOCTOR_REGISTRATION_EXISTS",
                    "A doctor with this registration already exists");
        }

        Doctor saved = doctorRepository.save(
                new Doctor(hospitalId, request.specialtyId(), request.name().trim(), council, number));
        return toResponse(saved);
    }

    /**
     * Doctor discovery: lists doctors sorted by name, optionally filtered by hospital and/or
     * specialty. An unknown filter id simply gives an empty page.
     *
     * @param hospitalId  optional hospital filter
     * @param specialtyId optional specialty filter
     * @param page        zero-based page number
     * @param size        page size; the controller keeps it within the allowed range
     * @return the requested page
     */
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CacheNames.DOCTORS,
            key = "'h:' + #hospitalId + ':s:' + #specialtyId + ':p:' + #page + ':' + #size")
    public PageResponse<DoctorResponse> list(UUID hospitalId, UUID specialtyId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("name").and(Sort.by("id")));
        Page<Doctor> result;
        if (hospitalId != null && specialtyId != null) {
            result = doctorRepository.findByHospitalIdAndSpecialtyId(hospitalId, specialtyId, pageable);
        } else if (hospitalId != null) {
            result = doctorRepository.findByHospitalId(hospitalId, pageable);
        } else if (specialtyId != null) {
            result = doctorRepository.findBySpecialtyId(specialtyId, pageable);
        } else {
            result = doctorRepository.findAll(pageable);
        }
        return PageResponse.of(result, this::toResponse);
    }

    /**
     * Finds one doctor.
     *
     * @param id doctor id
     * @return the doctor
     * @throws ApiException 404 when no doctor has this id (failures are not cached)
     */
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CacheNames.DOCTORS, key = "'id:' + #id")
    public DoctorResponse get(UUID id) {
        Doctor doctor = doctorRepository.findById(id).orElseThrow(() -> ApiException.notFound("Doctor", id));
        return toResponse(doctor);
    }

    /**
     * Cheap existence check for other modules.
     *
     * @param id doctor id
     * @return true when the doctor exists
     */
    @Transactional(readOnly = true)
    public boolean exists(UUID id) {
        return doctorRepository.existsById(id);
    }

    private DoctorResponse toResponse(Doctor doctor) {
        return new DoctorResponse(doctor.getId(), doctor.getHospitalId(), doctor.getSpecialtyId(), doctor.getName(),
                doctor.getRegistrationCouncil(), doctor.getRegistrationNumber(), doctor.getCreatedAt());
    }
}
