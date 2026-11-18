package com.hospitalqueue.patient.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import com.hospitalqueue.common.exception.ApiException;
import com.hospitalqueue.doctor.service.SlotService;
import com.hospitalqueue.patient.dto.QueueSnapshot;
import com.hospitalqueue.patient.dto.QueueSnapshot.ActiveToken;
import com.hospitalqueue.patient.entity.Token;
import com.hospitalqueue.patient.entity.TokenStatus;
import com.hospitalqueue.patient.repository.TokenRepository;
import com.hospitalqueue.patient.repository.TokenStatusCount;

/** Unit tests for {@link QueueSnapshotService} with a fixed clock; repositories are faked. */
@ExtendWith(MockitoExtension.class)
class QueueSnapshotServiceTest {

    private static final UUID SLOT_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-12T04:30:00Z");

    @Mock
    private TokenRepository tokenRepository;

    @Mock
    private SlotService slotService;

    private QueueSnapshotService snapshotService;

    @BeforeEach
    void setUp() {
        snapshotService = new QueueSnapshotService(tokenRepository, slotService, Clock.fixed(NOW, ZoneId.of("Asia/Kolkata")));
    }

    private static Token token(int number, TokenStatus status) {
        Token token = new Token(UUID.randomUUID(), UUID.randomUUID(), SLOT_ID, number);
        ReflectionTestUtils.setField(token, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(token, "status", status);
        return token;
    }

    @Test
    void getSnapshot_unknownSessionThrowsNotFoundWithoutReadingTokens() {
        when(slotService.exists(SLOT_ID)).thenReturn(false);

        assertThatThrownBy(() -> snapshotService.getSnapshot(SLOT_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));

        verifyNoInteractions(tokenRepository);
    }

    @Test
    void getSnapshot_listsTheActiveTokensInOrderAndCountsTheFinishedOnes() {
        Token first = token(1, TokenStatus.IN_CONSULTATION);
        Token second = token(2, TokenStatus.WAITING);
        Token fourth = token(4, TokenStatus.BOOKED);
        when(slotService.exists(SLOT_ID)).thenReturn(true);
        when(tokenRepository.findBySlotIdAndStatusInOrderByTokenNumberAsc(eq(SLOT_ID), eq(TokenStatus.ACTIVE)))
                .thenReturn(List.of(first, second, fourth));
        when(tokenRepository.countTokensByStatus(SLOT_ID)).thenReturn(List.of(
                new TokenStatusCount(TokenStatus.COMPLETED, 5L),
                new TokenStatusCount(TokenStatus.NO_SHOW, 2L),
                new TokenStatusCount(TokenStatus.CANCELLED, 1L),
                new TokenStatusCount(TokenStatus.WAITING, 1L)));

        QueueSnapshot snapshot = snapshotService.getSnapshot(SLOT_ID);

        assertThat(snapshot.slotId()).isEqualTo(SLOT_ID);
        assertThat(snapshot.activeTokens()).extracting(ActiveToken::tokenNumber).containsExactly(1, 2, 4);
        assertThat(snapshot.activeTokens()).extracting(ActiveToken::status)
                .containsExactly(TokenStatus.IN_CONSULTATION, TokenStatus.WAITING, TokenStatus.BOOKED);
        assertThat(snapshot.activeTokens().get(0).tokenId()).isEqualTo(first.getId());
        assertThat(snapshot.completedCount()).isEqualTo(5);
        assertThat(snapshot.noShowCount()).isEqualTo(2);
        assertThat(snapshot.cancelledCount()).isEqualTo(1);
        assertThat(snapshot.generatedAt()).isEqualTo(NOW);
    }

    @Test
    void getSnapshot_ofASessionWithNoTokensIsAllZeros() {
        when(slotService.exists(SLOT_ID)).thenReturn(true);
        when(tokenRepository.findBySlotIdAndStatusInOrderByTokenNumberAsc(eq(SLOT_ID), eq(TokenStatus.ACTIVE)))
                .thenReturn(List.of());
        when(tokenRepository.countTokensByStatus(SLOT_ID)).thenReturn(List.of());

        QueueSnapshot snapshot = snapshotService.getSnapshot(SLOT_ID);

        assertThat(snapshot.activeTokens()).isEmpty();
        assertThat(snapshot.completedCount()).isZero();
        assertThat(snapshot.noShowCount()).isZero();
        assertThat(snapshot.cancelledCount()).isZero();
    }
}
