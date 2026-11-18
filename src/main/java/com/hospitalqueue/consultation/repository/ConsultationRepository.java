package com.hospitalqueue.consultation.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hospitalqueue.consultation.entity.Consultation;

/** Data access for consultations. The unique constraint on token_id means a token has at most one consultation. */
public interface ConsultationRepository extends JpaRepository<Consultation, UUID> {

    /** The consultation of a token, if one was started. */
    Optional<Consultation> findByTokenId(UUID tokenId);
}
