package com.hospitalqueue.patient.dto;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.hospitalqueue.patient.entity.TokenStatus;

/**
 * Everything the live queue views need to know about one session, kept in Redis for a few seconds so that thousands of
 * polling patients cost one database read per few seconds instead of one each. It lists only the tokens still in the
 * queue (booked, waiting or in consultation) plus counts of the finished ones, so it stays small.
 *
 * @param slotId         the session
 * @param activeTokens   tokens still in the queue, lowest token number first
 * @param completedCount tokens whose consultation is finished
 * @param noShowCount    tokens whose patient did not come
 * @param cancelledCount cancelled tokens
 * @param generatedAt    when this snapshot was read from the database
 */
public record QueueSnapshot(
        UUID slotId,
        List<ActiveToken> activeTokens,
        int completedCount,
        int noShowCount,
        int cancelledCount,
        Instant generatedAt) implements Serializable {

    /**
     * A token that is still in the queue.
     *
     * @param tokenId     token id
     * @param tokenNumber the number patients are called by
     * @param status      BOOKED, WAITING or IN_CONSULTATION
     */
    public record ActiveToken(UUID tokenId, int tokenNumber, TokenStatus status) implements Serializable {
    }
}
