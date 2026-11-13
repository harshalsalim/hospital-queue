package com.hospitalqueue.admin.service;

import java.util.UUID;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hospitalqueue.admin.dto.CreateHospitalRequest;
import com.hospitalqueue.admin.dto.HospitalResponse;
import com.hospitalqueue.admin.entity.Hospital;
import com.hospitalqueue.admin.repository.HospitalRepository;
import com.hospitalqueue.common.config.CacheNames;
import com.hospitalqueue.common.dto.PageResponse;
import com.hospitalqueue.common.exception.ApiException;

/**
 * Business logic for hospital onboarding and lookup.
 *
 * <p>Reads are cached in Redis; creating a hospital evicts the cache after the transaction commits.
 * Other modules must call {@link #exists(UUID)} instead of reading the hospital table.
 */
@Service
public class HospitalService {

    private final HospitalRepository hospitalRepository;

    public HospitalService(HospitalRepository hospitalRepository) {
        this.hospitalRepository = hospitalRepository;
    }

    /**
     * Creates a hospital. Leading and trailing spaces are removed from name and address.
     * A hospital is identified by name plus address ignoring case: the check here gives a clean 409,
     * and the unique index {@code uq_hospital_name_address} (V4 migration) guarantees it even when
     * two identical requests arrive at the same time.
     *
     * @param request validated request body
     * @return the saved hospital
     * @throws ApiException 409 {@code HOSPITAL_EXISTS} when the same name and address already exist
     */
    @Transactional
    @CacheEvict(cacheNames = CacheNames.HOSPITALS, allEntries = true)
    public HospitalResponse create(CreateHospitalRequest request) {
        String name = request.name().trim();
        String address = request.address().trim();
        if (hospitalRepository.existsByNameIgnoreCaseAndAddressIgnoreCase(name, address)) {
            throw ApiException.conflict("HOSPITAL_EXISTS", "A hospital with this name and address already exists");
        }
        Hospital saved = hospitalRepository.save(new Hospital(name, address));
        return toResponse(saved);
    }

    /**
     * Lists hospitals sorted by name, one page at a time. The caller must pass a page size
     * within the allowed range; the controller enforces it.
     *
     * @param page zero-based page number
     * @param size page size
     * @return the requested page
     */
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CacheNames.HOSPITALS, key = "'page:' + #page + ':' + #size")
    public PageResponse<HospitalResponse> list(int page, int size) {
        Page<Hospital> result = hospitalRepository.findAll(PageRequest.of(page, size, Sort.by("name").and(Sort.by("id"))));
        return PageResponse.of(result, this::toResponse);
    }

    /**
     * Finds one hospital.
     *
     * @param id hospital id
     * @return the hospital
     * @throws ApiException 404 when no hospital has this id (failures are not cached)
     */
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CacheNames.HOSPITALS, key = "'id:' + #id")
    public HospitalResponse get(UUID id) {
        Hospital hospital = hospitalRepository.findById(id).orElseThrow(() -> ApiException.notFound("Hospital", id));
        return toResponse(hospital);
    }

    /**
     * Cheap existence check for other modules.
     *
     * @param id hospital id
     * @return true when the hospital exists
     */
    @Transactional(readOnly = true)
    public boolean exists(UUID id) {
        return hospitalRepository.existsById(id);
    }

    private HospitalResponse toResponse(Hospital hospital) {
        return new HospitalResponse(hospital.getId(), hospital.getName(), hospital.getAddress(), hospital.getCreatedAt());
    }
}
