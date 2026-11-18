package com.hospitalqueue.patient.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import com.hospitalqueue.common.config.CacheNames;
import com.hospitalqueue.common.exception.ApiException;
import com.hospitalqueue.doctor.service.SlotService;
import com.hospitalqueue.patient.dto.TokenResponse;
import com.hospitalqueue.patient.entity.TokenStatus;
import com.hospitalqueue.patient.repository.TokenQueueRepository;
import com.hospitalqueue.patient.repository.TokenRepository;

/**
 * Proves the cache wiring of the live queue: the snapshot of a session is read once and then served from the cache,
 * every successful queue change (check-in, call next, complete, no-show) evicts it, and a refused change does not.
 * Uses an in-memory cache, so no Redis, database or Docker is needed.
 */
@SpringJUnitConfig(QueueCachingTest.CachingTestConfig.class)
class QueueCachingTest {

    private static final UUID SLOT_ID = UUID.randomUUID();
    private static final UUID TOKEN_ID = UUID.randomUUID();

    @Configuration
    @EnableCaching
    static class CachingTestConfig {

        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager(CacheNames.QUEUE);
        }

        @Bean
        TokenRepository tokenRepository() {
            return Mockito.mock(TokenRepository.class);
        }

        @Bean
        TokenQueueRepository tokenQueueRepository() {
            return Mockito.mock(TokenQueueRepository.class);
        }

        @Bean
        SlotService slotService() {
            return Mockito.mock(SlotService.class);
        }

        @Bean
        TokenService tokenService() {
            return Mockito.mock(TokenService.class);
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            PlatformTransactionManager manager = Mockito.mock(PlatformTransactionManager.class);
            when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
            return manager;
        }

        @Bean
        TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
            return new TransactionTemplate(transactionManager);
        }

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-11-12T04:30:00Z"), ZoneId.of("Asia/Kolkata"));
        }

        @Bean
        QueueSnapshotService queueSnapshotService(TokenRepository tokenRepository, SlotService slotService, Clock clock) {
            return new QueueSnapshotService(tokenRepository, slotService, clock);
        }

        @Bean
        QueueService queueService(TokenService tokenService, TokenRepository tokenRepository,
                                  TokenQueueRepository tokenQueueRepository, SlotService slotService,
                                  TransactionTemplate transactionTemplate) {
            return new QueueService(tokenService, tokenRepository, tokenQueueRepository, slotService, transactionTemplate);
        }
    }

    @Autowired
    private QueueSnapshotService snapshotService;

    @Autowired
    private QueueService queueService;

    @Autowired
    private TokenRepository tokenRepository;

    @Autowired
    private TokenQueueRepository tokenQueueRepository;

    @Autowired
    private SlotService slotService;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void resetMocksAndCache() {
        Mockito.reset(tokenRepository, tokenQueueRepository, slotService, tokenService);
        for (String name : cacheManager.getCacheNames()) {
            Cache cache = cacheManager.getCache(name);
            if (cache != null) {
                cache.clear();
            }
        }
        when(slotService.exists(any(UUID.class))).thenReturn(true);
    }

    private static TokenResponse token(TokenStatus status) {
        return new TokenResponse(TOKEN_ID, UUID.randomUUID(), UUID.randomUUID(), SLOT_ID, 3, status,
                Instant.parse("2026-11-12T04:30:00Z"));
    }

    private void readTheQueueTwice() {
        snapshotService.getSnapshot(SLOT_ID);
        snapshotService.getSnapshot(SLOT_ID);
    }

    private void verifyTheDatabaseWasReadTimes(int times) {
        verify(tokenRepository, times(times))
                .findBySlotIdAndStatusInOrderByTokenNumberAsc(eq(SLOT_ID), anyCollection());
    }

    @Test
    void theSnapshotOfASessionIsReadOnceAndThenServedFromTheCache() {
        readTheQueueTwice();

        verifyTheDatabaseWasReadTimes(1);
    }

    @Test
    void everySessionHasItsOwnCacheEntry() {
        UUID otherSlot = UUID.randomUUID();

        snapshotService.getSnapshot(SLOT_ID);
        snapshotService.getSnapshot(otherSlot);
        snapshotService.getSnapshot(SLOT_ID);

        verifyTheDatabaseWasReadTimes(1);
        verify(tokenRepository, times(1)).findBySlotIdAndStatusInOrderByTokenNumberAsc(eq(otherSlot), anyCollection());
    }

    @Test
    void anUnknownSessionIsNeverCached() {
        when(slotService.exists(SLOT_ID)).thenReturn(false);

        assertThatThrownBy(() -> snapshotService.getSnapshot(SLOT_ID)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> snapshotService.getSnapshot(SLOT_ID)).isInstanceOf(ApiException.class);

        verify(slotService, times(2)).exists(SLOT_ID);
    }

    @Test
    void checkInEvictsTheSessionsSnapshot() {
        when(tokenRepository.checkIn(TOKEN_ID)).thenReturn(1);
        when(tokenService.get(TOKEN_ID)).thenReturn(token(TokenStatus.WAITING));
        snapshotService.getSnapshot(SLOT_ID);

        queueService.checkIn(TOKEN_ID);
        snapshotService.getSnapshot(SLOT_ID);

        verifyTheDatabaseWasReadTimes(2);
    }

    @Test
    void callNextEvictsTheSessionsSnapshot() {
        when(tokenRepository.existsBySlotIdAndStatus(SLOT_ID, TokenStatus.IN_CONSULTATION)).thenReturn(false);
        when(tokenQueueRepository.startNext(SLOT_ID)).thenReturn(Optional.of(TOKEN_ID));
        when(tokenService.get(TOKEN_ID)).thenReturn(token(TokenStatus.IN_CONSULTATION));
        snapshotService.getSnapshot(SLOT_ID);

        queueService.callNext(SLOT_ID);
        snapshotService.getSnapshot(SLOT_ID);

        verifyTheDatabaseWasReadTimes(2);
    }

    @Test
    void completeEvictsTheSessionsSnapshot() {
        when(tokenRepository.complete(TOKEN_ID)).thenReturn(1);
        when(tokenService.get(TOKEN_ID)).thenReturn(token(TokenStatus.COMPLETED));
        snapshotService.getSnapshot(SLOT_ID);

        queueService.complete(TOKEN_ID);
        snapshotService.getSnapshot(SLOT_ID);

        verifyTheDatabaseWasReadTimes(2);
    }

    @Test
    void noShowEvictsTheSessionsSnapshot() {
        when(tokenRepository.markNoShow(TOKEN_ID)).thenReturn(1);
        when(tokenService.get(TOKEN_ID)).thenReturn(token(TokenStatus.NO_SHOW));
        snapshotService.getSnapshot(SLOT_ID);

        queueService.markNoShow(TOKEN_ID);
        snapshotService.getSnapshot(SLOT_ID);

        verifyTheDatabaseWasReadTimes(2);
    }

    @Test
    void aRefusedChangeDoesNotEvictTheSnapshot() {
        when(tokenRepository.complete(TOKEN_ID)).thenReturn(0);
        when(tokenService.get(TOKEN_ID)).thenReturn(token(TokenStatus.BOOKED));
        snapshotService.getSnapshot(SLOT_ID);

        assertThatThrownBy(() -> queueService.complete(TOKEN_ID)).isInstanceOf(ApiException.class);
        snapshotService.getSnapshot(SLOT_ID);

        verifyTheDatabaseWasReadTimes(1);
    }
}
