package com.hospitalqueue.admin.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hospitalqueue.admin.entity.Specialty;

/** Data access for specialties. */
public interface SpecialtyRepository extends JpaRepository<Specialty, UUID> {

    /** True when a specialty with this name exists, ignoring upper/lower case. */
    boolean existsByNameIgnoreCase(String name);

    /** All specialties, sorted by name. The list is small, so it is not paged. */
    List<Specialty> findAllByOrderByNameAsc();
}
