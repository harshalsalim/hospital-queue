package com.hospitalqueue.consultation.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hospitalqueue.consultation.entity.PrescriptionItem;

/**
 * Data access for medicine lines. Items of many prescriptions are loaded with ONE query ({@link #findByPrescriptionIdIn}),
 * never one query per prescription, which avoids the N+1 query problem.
 */
public interface PrescriptionItemRepository extends JpaRepository<PrescriptionItem, UUID> {

    /** All medicine lines of the given prescriptions, in no particular order (callers sort by sortOrder). */
    List<PrescriptionItem> findByPrescriptionIdIn(Collection<UUID> prescriptionIds);
}
