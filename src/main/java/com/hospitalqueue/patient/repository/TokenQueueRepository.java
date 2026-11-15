package com.hospitalqueue.patient.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The one queue operation that needs a single SQL statement returning a value: pick and start the next patient.
 */
@Repository
public class TokenQueueRepository {

    private final JdbcClient jdbcClient;

    public TokenQueueRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * Starts the consultation of the next waiting patient of a session: the WAITING token with the lowest token
     * number becomes IN_CONSULTATION, in one atomic statement. {@code SKIP LOCKED} makes a simultaneous second call
     * move on to the next row instead of waiting; the unique index on one IN_CONSULTATION token per session then
     * stops it with a data-integrity error, so a doctor can never have two patients at once.
     *
     * @param slotId the session
     * @return the id of the token now in consultation, or empty when nobody is waiting
     */
    public Optional<UUID> startNext(UUID slotId) {
        return jdbcClient.sql("""
                        UPDATE token
                           SET status = 'IN_CONSULTATION',
                               updated_at = now()
                         WHERE id = (SELECT id
                                       FROM token
                                      WHERE slot_id = :slotId
                                        AND status = 'WAITING'
                                      ORDER BY token_number
                                      LIMIT 1
                                        FOR UPDATE SKIP LOCKED)
                        RETURNING id
                        """)
                .param("slotId", slotId)
                .query(UUID.class)
                .optional();
    }
}
