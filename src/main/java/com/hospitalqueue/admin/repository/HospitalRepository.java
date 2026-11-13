package com.hospitalqueue.admin.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hospitalqueue.admin.entity.Hospital;

/** Data access for hospitals. Paged queries come from {@code findAll(Pageable)}. */
public interface HospitalRepository extends JpaRepository<Hospital, UUID> {

    /** True when a hospital with this name at this address exists, ignoring upper/lower case. */
    boolean existsByNameIgnoreCaseAndAddressIgnoreCase(String name, String address);
}
