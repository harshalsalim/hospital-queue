package com.hospitalqueue.patient.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import com.hospitalqueue.common.exception.ApiException;
import com.hospitalqueue.patient.dto.QueueSnapshot;
import com.hospitalqueue.patient.dto.QueueSnapshot.ActiveToken;
import com.hospitalqueue.patient.dto.QueueStatusResponse;
import com.hospitalqueue.patient.dto.TokenPositionResponse;
import com.hospitalqueue.patient.entity.Token;
import com.hospitalqueue.patient.entity.TokenStatus;
import com.hospitalqueue.patient.repository.TokenRepository;

/**
 * Unit tests for {@link QueueStatusService}: the cached snapshot and the repository are faked. The average
 * consultation length is 10 minutes, so the estimated wait is 10 times the number of patients ahead.
 */
@ExtendWith(MockitoExtension.class)
class QueueStatusServiceTest {

    private static final UUID SLOT_ID = UUID.randomUUID();
    private static final Instant AS_OF = Instant.parse("2026-11-12T04:30:00Z");
    private static final int AVERAGE_MINUTES = 10;

    @Mock
    private QueueSnapshotService snapshotService;

    @Mock
    private TokenRepository tokenRepository;

    private QueueStatusService queueStatusService;

    @BeforeEach
    void setUp() {
        queueStatusService = new QueueStatusService(snapshotService, tokenRepository, AVERAGE_MINUTES);
    }

    private static ActiveToken active(int number, TokenStatus status) {
        return new ActiveToken(UUID.randomUUID(), number, status);
    }

    private static QueueSnapshot snapshot(int completed, int noShow, int cancelled, ActiveToken... tokens) {
        return new QueueSnapshot(SLOT_ID, List.of(tokens), completed, noShow, cancelled, AS_OF);
    }

    private static Token storedToken(UUID slotId, int number, TokenStatus status) {
        Token token = new Token(UUID.randomUUID(), UUID.randomUUID(), slotId, number);
        ReflectionTestUtils.setField(token, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(token, "status", status);
        return token;
    }

    // ---- getSlotQueue ----

    @Test
    void getSlotQueue_showsWhoIsServedWhoIsNextAndTheCounts() {
        when(snapshotService.getSnapshot(SLOT_ID)).thenReturn(snapshot(5, 1, 0,
                active(1, TokenStatus.IN_CONSULTATION),
                active(2, TokenStatus.WAITING),
                active(3, TokenStatus.WAITING),
                active(4, TokenStatus.BOOKED)));

        QueueStatusResponse response = queueStatusService.getSlotQueue(SLOT_ID);

        assertThat(response.slotId()).isEqualTo(SLOT_ID);
        assertThat(response.nowServingTokenNumber()).isEqualTo(1);
        assertThat(response.nextWaitingTokenNumber()).isEqualTo(2);
        assertThat(response.waitingCount()).isEqualTo(2);
        assertThat(response.bookedNotArrivedCount()).isEqualTo(1);
        assertThat(response.completedCount()).isEqualTo(5);
        assertThat(response.noShowCount()).isEqualTo(1);
        assertThat(response.cancelledCount()).isZero();
        assertThat(response.averageConsultationMinutes()).isEqualTo(AVERAGE_MINUTES);
        assertThat(response.asOf()).isEqualTo(AS_OF);
    }

    @Test
    void getSlotQueue_nextIsTheLowestWaitingNumberEvenWhenTheListIsNotSorted() {
        when(snapshotService.getSnapshot(SLOT_ID)).thenReturn(snapshot(0, 0, 0,
                active(7, TokenStatus.WAITING),
                active(3, TokenStatus.WAITING),
                active(5, TokenStatus.WAITING)));

        assertThat(queueStatusService.getSlotQueue(SLOT_ID).nextWaitingTokenNumber()).isEqualTo(3);
    }

    @Test
    void getSlotQueue_withNobodyServedOrWaitingHasNoNowServingAndNoNext() {
        when(snapshotService.getSnapshot(SLOT_ID)).thenReturn(snapshot(2, 0, 0, active(4, TokenStatus.BOOKED)));

        QueueStatusResponse response = queueStatusService.getSlotQueue(SLOT_ID);

        assertThat(response.nowServingTokenNumber()).isNull();
        assertThat(response.nextWaitingTokenNumber()).isNull();
        assertThat(response.waitingCount()).isZero();
        assertThat(response.bookedNotArrivedCount()).isEqualTo(1);
    }

    @Test
    void getSlotQueue_ofAnEmptySessionIsAllZeros() {
        when(snapshotService.getSnapshot(SLOT_ID)).thenReturn(snapshot(0, 0, 0));

        QueueStatusResponse response = queueStatusService.getSlotQueue(SLOT_ID);

        assertThat(response.waitingCount()).isZero();
        assertThat(response.bookedNotArrivedCount()).isZero();
        assertThat(response.nowServingTokenNumber()).isNull();
    }

    @Test
    void getSlotQueue_unknownSessionThrowsNotFound() {
        when(snapshotService.getSnapshot(SLOT_ID)).thenThrow(ApiException.notFound("Slot", SLOT_ID));

        assertThatThrownBy(() -> queueStatusService.getSlotQueue(SLOT_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ---- getTokenPosition: token is in the snapshot ----

    private QueueSnapshot standardQueue(ActiveToken serving, ActiveToken waitingTwo, ActiveToken waitingThree, ActiveToken booked) {
        return snapshot(0, 0, 0, serving, waitingTwo, waitingThree, booked);
    }

    @Test
    void getTokenPosition_aWaitingPatientCountsEveryoneStillInTheQueueWithALowerNumber() {
        ActiveToken serving = active(1, TokenStatus.IN_CONSULTATION);
        ActiveToken two = active(2, TokenStatus.WAITING);
        ActiveToken three = active(3, TokenStatus.WAITING);
        ActiveToken four = active(4, TokenStatus.BOOKED);
        when(snapshotService.getSnapshot(SLOT_ID)).thenReturn(standardQueue(serving, two, three, four));

        TokenPositionResponse response = queueStatusService.getTokenPosition(SLOT_ID, three.tokenId());

        assertThat(response.tokenNumber()).isEqualTo(3);
        assertThat(response.status()).isEqualTo(TokenStatus.WAITING);
        assertThat(response.tokensAhead()).isEqualTo(2);
        assertThat(response.estimatedWaitMinutes()).isEqualTo(20);
        assertThat(response.nowServingTokenNumber()).isEqualTo(1);
        assertThat(response.asOf()).isEqualTo(AS_OF);
    }

    @Test
    void getTokenPosition_theNextPatientHasOnlyTheOneBeingServedAhead() {
        ActiveToken serving = active(1, TokenStatus.IN_CONSULTATION);
        ActiveToken two = active(2, TokenStatus.WAITING);
        when(snapshotService.getSnapshot(SLOT_ID)).thenReturn(snapshot(0, 0, 0, serving, two));

        TokenPositionResponse response = queueStatusService.getTokenPosition(SLOT_ID, two.tokenId());

        assertThat(response.tokensAhead()).isEqualTo(1);
        assertThat(response.estimatedWaitMinutes()).isEqualTo(10);
    }

    @Test
    void getTokenPosition_aPatientNotYetArrivedAlsoGetsAnEstimate() {
        ActiveToken serving = active(1, TokenStatus.IN_CONSULTATION);
        ActiveToken two = active(2, TokenStatus.WAITING);
        ActiveToken three = active(3, TokenStatus.WAITING);
        ActiveToken four = active(4, TokenStatus.BOOKED);
        when(snapshotService.getSnapshot(SLOT_ID)).thenReturn(standardQueue(serving, two, three, four));

        TokenPositionResponse response = queueStatusService.getTokenPosition(SLOT_ID, four.tokenId());

        assertThat(response.status()).isEqualTo(TokenStatus.BOOKED);
        assertThat(response.tokensAhead()).isEqualTo(3);
        assertThat(response.estimatedWaitMinutes()).isEqualTo(30);
    }

    @Test
    void getTokenPosition_thePatientBeingServedHasNobodyAheadAndNoWait() {
        ActiveToken serving = active(5, TokenStatus.IN_CONSULTATION);
        ActiveToken notArrivedEarlier = active(2, TokenStatus.BOOKED);
        when(snapshotService.getSnapshot(SLOT_ID)).thenReturn(snapshot(0, 0, 0, notArrivedEarlier, serving));

        TokenPositionResponse response = queueStatusService.getTokenPosition(SLOT_ID, serving.tokenId());

        assertThat(response.status()).isEqualTo(TokenStatus.IN_CONSULTATION);
        assertThat(response.tokensAhead()).isZero();
        assertThat(response.estimatedWaitMinutes()).isZero();
        assertThat(response.nowServingTokenNumber()).isEqualTo(5);
    }

    // ---- getTokenPosition: token is not in the snapshot ----

    @Test
    void getTokenPosition_aFinishedTokenHasNoWaitingTime() {
        Token finished = storedToken(SLOT_ID, 1, TokenStatus.COMPLETED);
        when(snapshotService.getSnapshot(SLOT_ID)).thenReturn(snapshot(1, 0, 0, active(2, TokenStatus.WAITING)));
        when(tokenRepository.findById(finished.getId())).thenReturn(Optional.of(finished));

        TokenPositionResponse response = queueStatusService.getTokenPosition(SLOT_ID, finished.getId());

        assertThat(response.status()).isEqualTo(TokenStatus.COMPLETED);
        assertThat(response.tokensAhead()).isZero();
        assertThat(response.estimatedWaitMinutes()).isNull();
        verify(tokenRepository, never()).countBySlotIdAndTokenNumberLessThanAndStatusIn(
                eq(SLOT_ID), eq(1), eq(TokenStatus.ACTIVE));
    }

    @Test
    void getTokenPosition_aTokenBookedAfterTheSnapshotWasTakenIsCountedLive() {
        Token fresh = storedToken(SLOT_ID, 5, TokenStatus.BOOKED);
        when(snapshotService.getSnapshot(SLOT_ID)).thenReturn(snapshot(0, 0, 0, active(1, TokenStatus.WAITING)));
        when(tokenRepository.findById(fresh.getId())).thenReturn(Optional.of(fresh));
        when(tokenRepository.countBySlotIdAndTokenNumberLessThanAndStatusIn(eq(SLOT_ID), eq(5), eq(TokenStatus.ACTIVE)))
                .thenReturn(3L);

        TokenPositionResponse response = queueStatusService.getTokenPosition(SLOT_ID, fresh.getId());

        assertThat(response.status()).isEqualTo(TokenStatus.BOOKED);
        assertThat(response.tokensAhead()).isEqualTo(3);
        assertThat(response.estimatedWaitMinutes()).isEqualTo(30);
    }

    @Test
    void getTokenPosition_aTokenInConsultationMissingFromTheSnapshotHasNoWaitAndNoCountQuery() {
        Token serving = storedToken(SLOT_ID, 4, TokenStatus.IN_CONSULTATION);
        when(snapshotService.getSnapshot(SLOT_ID)).thenReturn(snapshot(0, 0, 0));
        when(tokenRepository.findById(serving.getId())).thenReturn(Optional.of(serving));

        TokenPositionResponse response = queueStatusService.getTokenPosition(SLOT_ID, serving.getId());

        assertThat(response.tokensAhead()).isZero();
        assertThat(response.estimatedWaitMinutes()).isZero();
        verify(tokenRepository, never()).countBySlotIdAndTokenNumberLessThanAndStatusIn(
                eq(SLOT_ID), eq(4), eq(TokenStatus.ACTIVE));
    }

    @Test
    void getTokenPosition_aTokenOfAnotherSessionIsNotFound() {
        Token foreign = storedToken(UUID.randomUUID(), 2, TokenStatus.WAITING);
        when(snapshotService.getSnapshot(SLOT_ID)).thenReturn(snapshot(0, 0, 0));
        when(tokenRepository.findById(foreign.getId())).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> queueStatusService.getTokenPosition(SLOT_ID, foreign.getId()))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void getTokenPosition_anUnknownTokenIsNotFound() {
        UUID unknown = UUID.randomUUID();
        when(snapshotService.getSnapshot(SLOT_ID)).thenReturn(snapshot(0, 0, 0));
        when(tokenRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> queueStatusService.getTokenPosition(SLOT_ID, unknown))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void getTokenPosition_anUnknownSessionIsNotFound() {
        when(snapshotService.getSnapshot(SLOT_ID)).thenThrow(ApiException.notFound("Slot", SLOT_ID));

        assertThatThrownBy(() -> queueStatusService.getTokenPosition(SLOT_ID, UUID.randomUUID()))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
