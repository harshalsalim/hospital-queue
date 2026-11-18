package com.hospitalqueue.patient.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hospitalqueue.common.config.CacheNames;
import com.hospitalqueue.common.exception.ApiException;
import com.hospitalqueue.doctor.service.SlotService;
import com.hospitalqueue.patient.dto.QueueSnapshot;
import com.hospitalqueue.patient.dto.QueueSnapshot.ActiveToken;
import com.hospitalqueue.patient.entity.TokenStatus;
import com.hospitalqueue.patient.repository.TokenRepository;
import com.hospitalqueue.patient.repository.TokenStatusCount;

/**
 * Reads a session's queue from the database and keeps it in Redis for 3 seconds.
 *
 * <p>This is the only place that reads the queue for the live views. It is a separate class so that the cache
 * annotation works: a cached method must be called from another bean. {@code QueueService} evicts the entry of a session
 * whenever its queue changes, so doctors' actions show up at once; the 3-second lifetime only covers changes that do not
 * evict, such as new bookings.
 */
@Service
public class QueueSnapshotService {

    private final TokenRepository tokenRepository;
    private final SlotService slotService;
    private final Clock clock;

    public QueueSnapshotService(TokenRepository tokenRepository, SlotService slotService, Clock clock) {
        this.tokenRepository = tokenRepository;
        this.slotService = slotService;
        this.clock = clock;
    }

    /**
     * Returns the queue snapshot of a session (from Redis when fresh, otherwise from the database).
     *
     * @param slotId the session
     * @return the snapshot
     * @throws ApiException 404 when the session does not exist (not cached)
     */
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CacheNames.QUEUE, key = "'slot:' + #slotId")
    public QueueSnapshot getSnapshot(UUID slotId) {
        if (!slotService.exists(slotId)) {
            throw ApiException.notFound("Slot", slotId);
        }

        List<ActiveToken> active = tokenRepository
                .findBySlotIdAndStatusInOrderByTokenNumberAsc(slotId, TokenStatus.ACTIVE).stream()
                .map(token -> new ActiveToken(token.getId(), token.getTokenNumber(), token.getStatus()))
                .toList();

        Map<TokenStatus, Long> counts = tokenRepository.countTokensByStatus(slotId).stream()
                .collect(Collectors.toMap(TokenStatusCount::status, TokenStatusCount::total));

        return new QueueSnapshot(slotId, active,
                counts.getOrDefault(TokenStatus.COMPLETED, 0L).intValue(),
                counts.getOrDefault(TokenStatus.NO_SHOW, 0L).intValue(),
                counts.getOrDefault(TokenStatus.CANCELLED, 0L).intValue(),
                Instant.now(clock));
    }
}
