package com.hospitalqueue.doctor.repository;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Atomic counter operations on the slot table, written in plain SQL on purpose.
 *
 * <p>{@link #takeNextToken(UUID)} is the heart of booking: the capacity check and the increment are
 * one UPDATE statement, so PostgreSQL's row lock makes concurrent bookings of the same session take
 * turns. No two bookings can get the same number, and the session can never be oversold, whatever
 * the number of application instances.
 */
@Repository
public class SlotCounterRepository {

    /** A snapshot of the columns that decide whether a session can take a booking. */
    public record SlotState(String status, int bookedCount, int maxTokens) {
    }

    private final JdbcClient jdbcClient;

    public SlotCounterRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * Takes the next token of a session in one atomic statement. It succeeds only while the session is
     * AVAILABLE and has tokens left. Must run inside the same transaction that stores the token, so a
     * failed insert also gives the number back.
     *
     * @param slotId the session
     * @return the new token number (the booked count after the increment), or empty when the session does
     *         not exist, is paused or is full
     */
    public OptionalInt takeNextToken(UUID slotId) {
        Optional<Integer> number = jdbcClient.sql("""
                        UPDATE slot
                           SET booked_count = booked_count + 1,
                               updated_at = now()
                         WHERE id = :id
                           AND status = 'AVAILABLE'
                           AND booked_count < max_tokens
                        RETURNING booked_count
                        """)
                .param("id", slotId)
                .query(Integer.class)
                .optional();
        return number.map(OptionalInt::of).orElseGet(OptionalInt::empty);
    }

    /**
     * Reads the current state straight from the database (never from a cached entity), used to explain
     * why {@link #takeNextToken(UUID)} found nothing to take.
     *
     * @param slotId the session
     * @return the state, or empty when the session does not exist
     */
    public Optional<SlotState> readState(UUID slotId) {
        return jdbcClient.sql("SELECT status, booked_count, max_tokens FROM slot WHERE id = :id")
                .param("id", slotId)
                .query((rs, rowNum) -> new SlotState(rs.getString("status"), rs.getInt("booked_count"), rs.getInt("max_tokens")))
                .optional();
    }
}
