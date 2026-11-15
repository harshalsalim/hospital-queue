package com.hospitalqueue.patient.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import com.hospitalqueue.common.exception.ApiException;
import com.hospitalqueue.doctor.service.SlotService;
import com.hospitalqueue.patient.dto.TokenResponse;
import com.hospitalqueue.patient.entity.TokenStatus;
import com.hospitalqueue.patient.repository.TokenQueueRepository;
import com.hospitalqueue.patient.repository.TokenRepository;

/**
 * Unit tests for {@link QueueService}. Collaborators are faked; a real TransactionTemplate runs on a fake transaction
 * manager so we can check rollbacks. The atomic SQL and the one-patient-at-a-time guarantee are proven against a real
 * database in QueueIntegrationTest.
 */
@ExtendWith(MockitoExtension.class)
class QueueServiceTest {

    private static final UUID TOKEN_ID = UUID.randomUUID();
    private static final UUID SLOT_ID = UUID.randomUUID();

    @Mock
    private TokenService tokenService;

    @Mock
    private TokenRepository tokenRepository;

    @Mock
    private TokenQueueRepository tokenQueueRepository;

    @Mock
    private SlotService slotService;

    @Mock
    private PlatformTransactionManager transactionManager;

    private QueueService queueService;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        queueService = new QueueService(tokenService, tokenRepository, tokenQueueRepository, slotService,
                new TransactionTemplate(transactionManager));
    }

    private static TokenResponse token(TokenStatus status) {
        return new TokenResponse(TOKEN_ID, UUID.randomUUID(), UUID.randomUUID(), SLOT_ID, 3, status,
                Instant.parse("2026-11-12T04:30:00Z"));
    }

    private static void assertApiError(Throwable thrown, HttpStatus status, String code) {
        assertThat(thrown).isInstanceOf(ApiException.class);
        ApiException exception = (ApiException) thrown;
        assertThat(exception.getStatus()).isEqualTo(status);
        assertThat(exception.getCode()).isEqualTo(code);
    }

    // ---- checkIn ----

    @Test
    void checkIn_movesABookedTokenToWaiting() {
        when(tokenRepository.checkIn(TOKEN_ID)).thenReturn(1);
        when(tokenService.get(TOKEN_ID)).thenReturn(token(TokenStatus.WAITING));

        assertThat(queueService.checkIn(TOKEN_ID).status()).isEqualTo(TokenStatus.WAITING);
    }

    @Test
    void checkIn_repeatedForAnAlreadyWaitingTokenIsFine() {
        when(tokenRepository.checkIn(TOKEN_ID)).thenReturn(0);
        when(tokenService.get(TOKEN_ID)).thenReturn(token(TokenStatus.WAITING));

        assertThat(queueService.checkIn(TOKEN_ID).status()).isEqualTo(TokenStatus.WAITING);
    }

    @Test
    void checkIn_isRefusedForATokenThatIsPastThisStep() {
        when(tokenRepository.checkIn(TOKEN_ID)).thenReturn(0);
        when(tokenService.get(TOKEN_ID)).thenReturn(token(TokenStatus.IN_CONSULTATION));

        assertThatThrownBy(() -> queueService.checkIn(TOKEN_ID))
                .satisfies(thrown -> {
                    assertApiError(thrown, HttpStatus.CONFLICT, "INVALID_TOKEN_STATE");
                    assertThat(thrown.getMessage()).contains("IN_CONSULTATION");
                });
    }

    @Test
    void checkIn_unknownTokenThrowsNotFound() {
        when(tokenRepository.checkIn(TOKEN_ID)).thenReturn(0);
        when(tokenService.get(TOKEN_ID)).thenThrow(ApiException.notFound("Token", TOKEN_ID));

        assertThatThrownBy(() -> queueService.checkIn(TOKEN_ID))
                .satisfies(thrown -> assertApiError(thrown, HttpStatus.NOT_FOUND, "NOT_FOUND"));
    }

    // ---- complete ----

    @Test
    void complete_finishesAConsultationInProgress() {
        when(tokenRepository.complete(TOKEN_ID)).thenReturn(1);
        when(tokenService.get(TOKEN_ID)).thenReturn(token(TokenStatus.COMPLETED));

        assertThat(queueService.complete(TOKEN_ID).status()).isEqualTo(TokenStatus.COMPLETED);
    }

    @Test
    void complete_repeatedForAnAlreadyCompletedTokenIsFine() {
        when(tokenRepository.complete(TOKEN_ID)).thenReturn(0);
        when(tokenService.get(TOKEN_ID)).thenReturn(token(TokenStatus.COMPLETED));

        assertThat(queueService.complete(TOKEN_ID).status()).isEqualTo(TokenStatus.COMPLETED);
    }

    @Test
    void complete_isRefusedForAPatientWhoWasNeverCalled() {
        when(tokenRepository.complete(TOKEN_ID)).thenReturn(0);
        when(tokenService.get(TOKEN_ID)).thenReturn(token(TokenStatus.BOOKED));

        assertThatThrownBy(() -> queueService.complete(TOKEN_ID))
                .satisfies(thrown -> assertApiError(thrown, HttpStatus.CONFLICT, "INVALID_TOKEN_STATE"));
    }

    // ---- markNoShow ----

    @Test
    void markNoShow_marksAWaitingPatientAsNotPresent() {
        when(tokenRepository.markNoShow(TOKEN_ID)).thenReturn(1);
        when(tokenService.get(TOKEN_ID)).thenReturn(token(TokenStatus.NO_SHOW));

        assertThat(queueService.markNoShow(TOKEN_ID).status()).isEqualTo(TokenStatus.NO_SHOW);
    }

    @Test
    void markNoShow_repeatedForAnAlreadyNoShowTokenIsFine() {
        when(tokenRepository.markNoShow(TOKEN_ID)).thenReturn(0);
        when(tokenService.get(TOKEN_ID)).thenReturn(token(TokenStatus.NO_SHOW));

        assertThat(queueService.markNoShow(TOKEN_ID).status()).isEqualTo(TokenStatus.NO_SHOW);
    }

    @Test
    void markNoShow_isRefusedForATokenThatIsAlreadyFinished() {
        when(tokenRepository.markNoShow(TOKEN_ID)).thenReturn(0);
        when(tokenService.get(TOKEN_ID)).thenReturn(token(TokenStatus.COMPLETED));

        assertThatThrownBy(() -> queueService.markNoShow(TOKEN_ID))
                .satisfies(thrown -> assertApiError(thrown, HttpStatus.CONFLICT, "INVALID_TOKEN_STATE"));
    }

    // ---- callNext ----

    @Test
    void callNext_unknownSessionThrowsNotFoundWithoutTouchingTokens() {
        when(slotService.exists(SLOT_ID)).thenReturn(false);

        assertThatThrownBy(() -> queueService.callNext(SLOT_ID))
                .satisfies(thrown -> assertApiError(thrown, HttpStatus.NOT_FOUND, "NOT_FOUND"));

        verifyNoInteractions(tokenRepository, tokenQueueRepository);
    }

    @Test
    void callNext_isRefusedWhileAPatientIsStillInConsultation() {
        when(slotService.exists(SLOT_ID)).thenReturn(true);
        when(tokenRepository.existsBySlotIdAndStatus(SLOT_ID, TokenStatus.IN_CONSULTATION)).thenReturn(true);

        assertThatThrownBy(() -> queueService.callNext(SLOT_ID))
                .satisfies(thrown -> assertApiError(thrown, HttpStatus.CONFLICT, "CONSULTATION_IN_PROGRESS"));

        verify(tokenQueueRepository, never()).startNext(any(UUID.class));
    }

    @Test
    void callNext_withNobodyWaitingReportsAnEmptyQueue() {
        when(slotService.exists(SLOT_ID)).thenReturn(true);
        when(tokenRepository.existsBySlotIdAndStatus(SLOT_ID, TokenStatus.IN_CONSULTATION)).thenReturn(false);
        when(tokenQueueRepository.startNext(SLOT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> queueService.callNext(SLOT_ID))
                .satisfies(thrown -> assertApiError(thrown, HttpStatus.CONFLICT, "QUEUE_EMPTY"));
    }

    @Test
    void callNext_returnsTheTokenThatIsNowInConsultation() {
        when(slotService.exists(SLOT_ID)).thenReturn(true);
        when(tokenRepository.existsBySlotIdAndStatus(SLOT_ID, TokenStatus.IN_CONSULTATION)).thenReturn(false);
        when(tokenQueueRepository.startNext(SLOT_ID)).thenReturn(Optional.of(TOKEN_ID));
        when(tokenService.get(TOKEN_ID)).thenReturn(token(TokenStatus.IN_CONSULTATION));

        TokenResponse result = queueService.callNext(SLOT_ID);

        assertThat(result.status()).isEqualTo(TokenStatus.IN_CONSULTATION);
        assertThat(result.id()).isEqualTo(TOKEN_ID);
        verify(transactionManager).commit(any());
    }

    @Test
    void callNext_aSimultaneousCallThatLostTheRaceIsToldAConsultationIsInProgress() {
        when(slotService.exists(SLOT_ID)).thenReturn(true);
        when(tokenRepository.existsBySlotIdAndStatus(SLOT_ID, TokenStatus.IN_CONSULTATION)).thenReturn(false);
        when(tokenQueueRepository.startNext(SLOT_ID)).thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> queueService.callNext(SLOT_ID))
                .satisfies(thrown -> assertApiError(thrown, HttpStatus.CONFLICT, "CONSULTATION_IN_PROGRESS"));

        verify(transactionManager).rollback(any());
    }
}
