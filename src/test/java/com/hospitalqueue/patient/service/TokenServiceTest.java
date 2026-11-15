package com.hospitalqueue.patient.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import com.hospitalqueue.common.exception.ApiException;
import com.hospitalqueue.doctor.dto.TokenAllocation;
import com.hospitalqueue.doctor.service.SlotBookingService;
import com.hospitalqueue.patient.dto.TokenResponse;
import com.hospitalqueue.patient.entity.Token;
import com.hospitalqueue.patient.entity.TokenStatus;
import com.hospitalqueue.patient.repository.TokenRepository;
import com.hospitalqueue.patient.service.TokenService.BookingResult;

/**
 * Unit tests for {@link TokenService}. Collaborators are faked; a real TransactionTemplate runs on top of a
 * fake transaction manager so we can check that failures roll back. The real database behaviour under
 * concurrency is proven in TokenBookingConcurrencyTest.
 */
@ExtendWith(MockitoExtension.class)
class TokenServiceTest {

    private static final UUID PATIENT_ID = UUID.randomUUID();
    private static final UUID SLOT_ID = UUID.randomUUID();
    private static final UUID HOSPITAL_ID = UUID.randomUUID();
    private static final UUID DOCTOR_ID = UUID.randomUUID();

    @Mock
    private PatientService patientService;

    @Mock
    private SlotBookingService slotBookingService;

    @Mock
    private TokenRepository tokenRepository;

    @Mock
    private PlatformTransactionManager transactionManager;

    private TokenService tokenService;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        tokenService = new TokenService(patientService, slotBookingService, tokenRepository,
                new TransactionTemplate(transactionManager));
    }

    private static Token tokenWithNumber(int number) {
        Token token = new Token(HOSPITAL_ID, PATIENT_ID, SLOT_ID, number);
        ReflectionTestUtils.setField(token, "id", UUID.randomUUID());
        return token;
    }

    private void noActiveTokenYet() {
        when(tokenRepository.findFirstByPatientIdAndSlotIdAndStatusIn(eq(PATIENT_ID), eq(SLOT_ID), anyCollection()))
                .thenReturn(Optional.empty());
    }

    // ---- book ----

    @Test
    void book_createsATokenWithTheNumberFromTheSlotCounterAndCommits() {
        when(patientService.exists(PATIENT_ID)).thenReturn(true);
        noActiveTokenYet();
        when(slotBookingService.takeToken(SLOT_ID)).thenReturn(new TokenAllocation(SLOT_ID, HOSPITAL_ID, DOCTOR_ID, 7));
        when(tokenRepository.saveAndFlush(any(Token.class))).thenAnswer(invocation -> invocation.getArgument(0));

        BookingResult result = tokenService.book(PATIENT_ID, SLOT_ID);

        assertThat(result.created()).isTrue();
        assertThat(result.token().tokenNumber()).isEqualTo(7);
        assertThat(result.token().status()).isEqualTo(TokenStatus.BOOKED);
        assertThat(result.token().hospitalId()).isEqualTo(HOSPITAL_ID);
        assertThat(result.token().patientId()).isEqualTo(PATIENT_ID);
        verify(transactionManager).commit(any());
    }

    @Test
    void book_returnsTheExistingActiveTokenWithoutTakingANewNumber() {
        Token existing = tokenWithNumber(3);
        when(patientService.exists(PATIENT_ID)).thenReturn(true);
        when(tokenRepository.findFirstByPatientIdAndSlotIdAndStatusIn(eq(PATIENT_ID), eq(SLOT_ID), anyCollection()))
                .thenReturn(Optional.of(existing));

        BookingResult result = tokenService.book(PATIENT_ID, SLOT_ID);

        assertThat(result.created()).isFalse();
        assertThat(result.token().tokenNumber()).isEqualTo(3);
        verifyNoInteractions(slotBookingService);
        verify(tokenRepository, never()).saveAndFlush(any(Token.class));
    }

    @Test
    void book_anUnknownPatientThrowsNotFoundAndTouchesNothingElse() {
        when(patientService.exists(PATIENT_ID)).thenReturn(false);

        assertThatThrownBy(() -> tokenService.book(PATIENT_ID, SLOT_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));

        verifyNoInteractions(slotBookingService, tokenRepository);
    }

    @Test
    void book_aFullSessionPropagatesTheErrorAndRollsBackWithoutSavingAToken() {
        when(patientService.exists(PATIENT_ID)).thenReturn(true);
        noActiveTokenYet();
        when(slotBookingService.takeToken(SLOT_ID))
                .thenThrow(ApiException.conflict("SLOT_FULL", "All tokens for this session are booked"));

        assertThatThrownBy(() -> tokenService.book(PATIENT_ID, SLOT_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).getCode()).isEqualTo("SLOT_FULL"));

        verify(tokenRepository, never()).saveAndFlush(any(Token.class));
        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
    }

    @Test
    void book_aSimultaneousIdenticalRequestThatLostTheRaceGetsTheWinnersToken() {
        Token winner = tokenWithNumber(2);
        when(patientService.exists(PATIENT_ID)).thenReturn(true);
        when(tokenRepository.findFirstByPatientIdAndSlotIdAndStatusIn(eq(PATIENT_ID), eq(SLOT_ID), anyCollection()))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(slotBookingService.takeToken(SLOT_ID)).thenReturn(new TokenAllocation(SLOT_ID, HOSPITAL_ID, DOCTOR_ID, 3));
        when(tokenRepository.saveAndFlush(any(Token.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        BookingResult result = tokenService.book(PATIENT_ID, SLOT_ID);

        assertThat(result.created()).isFalse();
        assertThat(result.token().id()).isEqualTo(winner.getId());
        verify(transactionManager).rollback(any());
    }

    @Test
    void book_aDatabaseConflictThatIsNotADuplicateIsRethrown() {
        when(patientService.exists(PATIENT_ID)).thenReturn(true);
        when(tokenRepository.findFirstByPatientIdAndSlotIdAndStatusIn(eq(PATIENT_ID), eq(SLOT_ID), anyCollection()))
                .thenReturn(Optional.empty(), Optional.empty());
        when(slotBookingService.takeToken(SLOT_ID)).thenReturn(new TokenAllocation(SLOT_ID, HOSPITAL_ID, DOCTOR_ID, 1));
        when(tokenRepository.saveAndFlush(any(Token.class)))
                .thenThrow(new DataIntegrityViolationException("foreign key violation"));

        assertThatThrownBy(() -> tokenService.book(PATIENT_ID, SLOT_ID))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---- hasActiveToken ----

    @Test
    void hasActiveToken_isTrueWhenThePatientHoldsAnActiveTokenInTheSession() {
        when(tokenRepository.findFirstByPatientIdAndSlotIdAndStatusIn(eq(PATIENT_ID), eq(SLOT_ID), anyCollection()))
                .thenReturn(Optional.of(tokenWithNumber(2)));

        assertThat(tokenService.hasActiveToken(PATIENT_ID, SLOT_ID)).isTrue();
    }

    @Test
    void hasActiveToken_isFalseWhenThePatientHasNoActiveTokenInTheSession() {
        noActiveTokenYet();

        assertThat(tokenService.hasActiveToken(PATIENT_ID, SLOT_ID)).isFalse();
    }

    // ---- get ----

    @Test
    void get_returnsTheToken() {
        Token token = tokenWithNumber(5);
        when(tokenRepository.findById(token.getId())).thenReturn(Optional.of(token));

        TokenResponse response = tokenService.get(token.getId());

        assertThat(response.tokenNumber()).isEqualTo(5);
        assertThat(response.id()).isEqualTo(token.getId());
    }

    @Test
    void get_unknownIdThrowsNotFound() {
        UUID id = UUID.randomUUID();
        when(tokenRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tokenService.get(id))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ---- listForPatient ----

    @Test
    void listForPatient_returnsThePatientsTokensInRepositoryOrder() {
        when(patientService.exists(PATIENT_ID)).thenReturn(true);
        when(tokenRepository.findTop50ByPatientIdOrderByBookedAtDesc(PATIENT_ID))
                .thenReturn(List.of(tokenWithNumber(9), tokenWithNumber(4)));

        List<TokenResponse> result = tokenService.listForPatient(PATIENT_ID);

        assertThat(result).extracting(TokenResponse::tokenNumber).containsExactly(9, 4);
    }

    @Test
    void listForPatient_unknownPatientThrowsNotFound() {
        when(patientService.exists(PATIENT_ID)).thenReturn(false);

        assertThatThrownBy(() -> tokenService.listForPatient(PATIENT_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));

        verifyNoInteractions(tokenRepository);
    }
}
