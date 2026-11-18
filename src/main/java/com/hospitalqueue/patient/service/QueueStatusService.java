package com.hospitalqueue.patient.service;

import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hospitalqueue.common.exception.ApiException;
import com.hospitalqueue.patient.dto.QueueSnapshot;
import com.hospitalqueue.patient.dto.QueueSnapshot.ActiveToken;
import com.hospitalqueue.patient.dto.QueueStatusResponse;
import com.hospitalqueue.patient.dto.TokenPositionResponse;
import com.hospitalqueue.patient.entity.Token;
import com.hospitalqueue.patient.entity.TokenStatus;
import com.hospitalqueue.patient.repository.TokenRepository;

/**
 * The live queue views: the session's queue as a whole, and one patient's place in it.
 *
 * <p>Both views are computed in memory from the cached {@link QueueSnapshot}, so any number of polling patients cost
 * almost no database work. The waiting time is an estimate: patients ahead times the average consultation length
 * (setting {@code app.queue.average-consultation-minutes}).
 */
@Service
public class QueueStatusService {

    private final QueueSnapshotService snapshotService;
    private final TokenRepository tokenRepository;
    private final int averageConsultationMinutes;

    public QueueStatusService(QueueSnapshotService snapshotService,
                              TokenRepository tokenRepository,
                              @Value("${app.queue.average-consultation-minutes:10}") int averageConsultationMinutes) {
        this.snapshotService = snapshotService;
        this.tokenRepository = tokenRepository;
        this.averageConsultationMinutes = averageConsultationMinutes;
    }

    /**
     * The live queue of a session.
     *
     * @param slotId the session
     * @return who is being served, who is next, and the counts
     * @throws ApiException 404 when the session does not exist
     */
    public QueueStatusResponse getSlotQueue(UUID slotId) {
        QueueSnapshot snapshot = snapshotService.getSnapshot(slotId);

        Integer nextWaiting = snapshot.activeTokens().stream()
                .filter(token -> token.status() == TokenStatus.WAITING)
                .min(Comparator.comparingInt(ActiveToken::tokenNumber))
                .map(ActiveToken::tokenNumber)
                .orElse(null);

        return new QueueStatusResponse(slotId, nowServing(snapshot), nextWaiting,
                count(snapshot, TokenStatus.WAITING), count(snapshot, TokenStatus.BOOKED),
                snapshot.completedCount(), snapshot.noShowCount(), snapshot.cancelledCount(),
                averageConsultationMinutes, snapshot.generatedAt());
    }

    /**
     * One patient's place in the queue of a session.
     *
     * @param slotId  the session
     * @param tokenId the patient's token
     * @return tokens ahead and the estimated wait
     * @throws ApiException 404 when the session does not exist, or the token does not exist or belongs to another session
     */
    @Transactional(readOnly = true)
    public TokenPositionResponse getTokenPosition(UUID slotId, UUID tokenId) {
        QueueSnapshot snapshot = snapshotService.getSnapshot(slotId);

        Optional<ActiveToken> inQueue = snapshot.activeTokens().stream()
                .filter(token -> token.tokenId().equals(tokenId))
                .findFirst();
        if (inQueue.isPresent()) {
            ActiveToken mine = inQueue.get();
            int ahead = mine.status() == TokenStatus.IN_CONSULTATION ? 0 : (int) snapshot.activeTokens().stream()
                    .filter(token -> token.tokenNumber() < mine.tokenNumber())
                    .count();
            return position(mine.tokenId(), mine.tokenNumber(), mine.status(), ahead, snapshot);
        }

        // Not in the snapshot: either the token is finished, or it was booked after the snapshot was taken.
        Token token = tokenRepository.findById(tokenId)
                .filter(found -> slotId.equals(found.getSlotId()))
                .orElseThrow(() -> ApiException.notFound("Token", tokenId));
        if (!TokenStatus.ACTIVE.contains(token.getStatus())) {
            return new TokenPositionResponse(tokenId, token.getTokenNumber(), token.getStatus(), 0, null,
                    nowServing(snapshot), snapshot.generatedAt());
        }
        int ahead = token.getStatus() == TokenStatus.IN_CONSULTATION ? 0 : (int) tokenRepository
                .countBySlotIdAndTokenNumberLessThanAndStatusIn(slotId, token.getTokenNumber(), TokenStatus.ACTIVE);
        return position(tokenId, token.getTokenNumber(), token.getStatus(), ahead, snapshot);
    }

    private TokenPositionResponse position(UUID tokenId, int tokenNumber, TokenStatus status, int ahead, QueueSnapshot snapshot) {
        int estimatedWait = status == TokenStatus.IN_CONSULTATION ? 0 : ahead * averageConsultationMinutes;
        return new TokenPositionResponse(tokenId, tokenNumber, status, ahead, estimatedWait,
                nowServing(snapshot), snapshot.generatedAt());
    }

    private static Integer nowServing(QueueSnapshot snapshot) {
        return snapshot.activeTokens().stream()
                .filter(token -> token.status() == TokenStatus.IN_CONSULTATION)
                .map(ActiveToken::tokenNumber)
                .findFirst()
                .orElse(null);
    }

    private static int count(QueueSnapshot snapshot, TokenStatus status) {
        return (int) snapshot.activeTokens().stream().filter(token -> token.status() == status).count();
    }
}
