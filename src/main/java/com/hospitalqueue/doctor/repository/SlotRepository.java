package com.hospitalqueue.doctor.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.hospitalqueue.doctor.entity.Slot;

/**
 * Data access for slots. Changes to existing rows use single atomic UPDATE statements (never
 * read-modify-write), so concurrent requests cannot overwrite each other's changes.
 */
public interface SlotRepository extends JpaRepository<Slot, UUID> {

    /** A doctor's sessions on one day, earliest first. Uses the unique index on (doctor, date, start). */
    List<Slot> findByDoctorIdAndSlotDateOrderByStartTimeAsc(UUID doctorId, LocalDate slotDate);

    /**
     * True when the doctor already has a session on that day overlapping [newStart, newEnd).
     * Sessions that only touch (one ends exactly when the next starts) do not overlap.
     */
    @Query("select count(s) > 0 from Slot s where s.doctorId = :doctorId and s.slotDate = :date "
            + "and s.startTime < :newEnd and s.endTime > :newStart")
    boolean existsOverlapping(@Param("doctorId") UUID doctorId,
                              @Param("date") LocalDate date,
                              @Param("newStart") LocalTime newStart,
                              @Param("newEnd") LocalTime newEnd);

    /**
     * Sets the status of a session.
     *
     * @return number of rows changed: 1, or 0 when the slot does not exist
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE slot SET status = :status, updated_at = :now WHERE id = :id", nativeQuery = true)
    int updateStatus(@Param("id") UUID id, @Param("status") String status, @Param("now") Instant now);

    /**
     * Sets the token limit, but only when it is not below the tokens already booked. The check and the
     * change happen in one statement, so a booking that lands at the same moment cannot be violated.
     *
     * @return number of rows changed: 1, or 0 when the slot does not exist or the limit is below booked_count
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE slot SET max_tokens = :maxTokens, updated_at = :now "
            + "WHERE id = :id AND booked_count <= :maxTokens", nativeQuery = true)
    int updateMaxTokens(@Param("id") UUID id, @Param("maxTokens") int maxTokens, @Param("now") Instant now);
}
