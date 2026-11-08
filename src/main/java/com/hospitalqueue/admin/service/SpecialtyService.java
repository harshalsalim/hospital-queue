package com.hospitalqueue.admin.service;

import java.util.List;
import java.util.UUID;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hospitalqueue.admin.dto.CreateSpecialtyRequest;
import com.hospitalqueue.admin.dto.SpecialtyResponse;
import com.hospitalqueue.admin.entity.Specialty;
import com.hospitalqueue.admin.repository.SpecialtyRepository;
import com.hospitalqueue.common.config.CacheNames;
import com.hospitalqueue.common.exception.ApiException;

/**
 * Business logic for the specialty list used by doctor discovery.
 *
 * <p>The list is read very often and changes rarely, so it is cached in Redis for an hour
 * and evicted whenever a specialty is created.
 */
@Service
public class SpecialtyService {

    private final SpecialtyRepository specialtyRepository;

    public SpecialtyService(SpecialtyRepository specialtyRepository) {
        this.specialtyRepository = specialtyRepository;
    }

    /**
     * Creates a specialty. Names are unique ignoring case: the friendly check here gives a clean
     * 409, and the unique index {@code uq_specialty_name_lower} (V2 migration) guarantees it even
     * when two requests race.
     *
     * @param request validated request body
     * @return the saved specialty
     * @throws ApiException 409 {@code SPECIALTY_EXISTS} when the name is already used
     */
    @Transactional
    @CacheEvict(cacheNames = CacheNames.SPECIALTIES, allEntries = true)
    public SpecialtyResponse create(CreateSpecialtyRequest request) {
        String name = request.name().trim();
        if (specialtyRepository.existsByNameIgnoreCase(name)) {
            throw ApiException.conflict("SPECIALTY_EXISTS", "Specialty already exists: " + name);
        }
        Specialty saved = specialtyRepository.save(new Specialty(name));
        return toResponse(saved);
    }

    /**
     * Lists all specialties sorted by name.
     *
     * @return all specialties
     */
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CacheNames.SPECIALTIES, key = "'all'")
    public List<SpecialtyResponse> list() {
        return specialtyRepository.findAllByOrderByNameAsc().stream().map(this::toResponse).toList();
    }

    /**
     * Cheap existence check for other modules.
     *
     * @param id specialty id
     * @return true when the specialty exists
     */
    @Transactional(readOnly = true)
    public boolean exists(UUID id) {
        return specialtyRepository.existsById(id);
    }

    private SpecialtyResponse toResponse(Specialty specialty) {
        return new SpecialtyResponse(specialty.getId(), specialty.getName());
    }
}
