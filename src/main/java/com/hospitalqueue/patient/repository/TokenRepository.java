package com.hospitalqueue.patient.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.hospitalqueue.patient.entity.Token;
import com.hospitalqueue.patient.entity.TokenStatus;

/**
 * Data access for tokens. Lookups use the indexes on patient_id and the unique (slot, token number) index.
 *
 * <p>Status changes in the queue are single conditional UPDATE statements ("only if the token is still in this
 * state"), so two devices acting on the same token cannot both succeed. Each returns the number of rows changed:
 * 1 = this call did it, 0 = the token does not exist or is not in a state that allows the change.
 */
public interface TokenRepository extends JpaRepository<Token, UUID> {

    /** The patient's token in this session whose status is one of the given statuses, if any. */
    Optional<Token> findFirstByPatientIdAndSlotIdAndStatusIn(UUID patientId, UUID slotId, Collection<TokenStatus> statuses);

    /** The patient's 50 most recent tokens, newest first. */
    List<Token> findTop50ByPatientIdOrderByBookedAtDesc(UUID patientId);

    /** True when a token of this session currently has the given status. */
    boolean existsBySlotIdAndStatus(UUID slotId, TokenStatus status);

    /** BOOKED to WAITING: the patient has arrived. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE token SET status = 'WAITING', updated_at = now() "
            + "WHERE id = :id AND status = 'BOOKED'", nativeQuery = true)
    int checkIn(@Param("id") UUID id);

    /** IN_CONSULTATION to COMPLETED: the consultation is finished. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE token SET status = 'COMPLETED', completed_at = now(), updated_at = now() "
            + "WHERE id = :id AND status = 'IN_CONSULTATION'", nativeQuery = true)
    int complete(@Param("id") UUID id);

    /** BOOKED, WAITING or IN_CONSULTATION to NO_SHOW: the patient did not come. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE token SET status = 'NO_SHOW', completed_at = now(), updated_at = now() "
            + "WHERE id = :id AND status IN ('BOOKED', 'WAITING', 'IN_CONSULTATION')", nativeQuery = true)
    int markNoShow(@Param("id") UUID id);
}
