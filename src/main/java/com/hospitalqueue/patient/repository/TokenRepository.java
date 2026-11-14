package com.hospitalqueue.patient.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hospitalqueue.patient.entity.Token;
import com.hospitalqueue.patient.entity.TokenStatus;

/** Data access for tokens. Lookups use the indexes on patient_id and the unique (slot, token number) index. */
public interface TokenRepository extends JpaRepository<Token, UUID> {

    /** The patient's token in this session whose status is one of the given statuses, if any. */
    Optional<Token> findFirstByPatientIdAndSlotIdAndStatusIn(UUID patientId, UUID slotId, Collection<TokenStatus> statuses);

    /** The patient's 50 most recent tokens, newest first. */
    List<Token> findTop50ByPatientIdOrderByBookedAtDesc(UUID patientId);
}
