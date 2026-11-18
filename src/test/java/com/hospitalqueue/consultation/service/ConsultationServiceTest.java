package com.hospitalqueue.consultation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import com.hospitalqueue.common.exception.ApiException;
import com.hospitalqueue.consultation.dto.ConsultationResponse;
import com.hospitalqueue.consultation.entity.Consultation;
import com.hospitalqueue.consultation.repository.ConsultationRepository;
import com.hospitalqueue.consultation.service.ConsultationService.StartResult;
import com.hospitalqueue.doctor.dto.SlotResponse;
import com.hospitalqueue.doctor.entity.SlotStatus;
import com.hospitalqueue.doctor.service.SlotService;
import com.hospitalqueue.patient.dto.TokenResponse;
import com.hospitalqueue.patient.entity.TokenStatus;
import com.hospitalqueue.patient.service.TokenService;

/** Unit tests for {@link ConsultationService}: repository and other modules' services are faked. */
@ExtendWith(MockitoExtension.class)
class ConsultationServiceTest {

    private static final UUID CONSULTATION_ID = UUID.randomUUID();
    private static final UUID TOKEN_ID = UUID.randomUUID();
    private static final UUID SLOT_ID = UUID.randomUUID();
    private static final UUID HOSPITAL_ID = UUID.randomUUID();
    private static final UUID PATIENT_ID = UUID.randomUUID();
    private static final UUID DOCTOR_ID = UUID.randomUUID();

    @Mock
    private ConsultationRepository consultationRepository;

    @Mock
    private TokenService tokenService;

    @Mock
    private SlotService slotService;

    private ConsultationService consultationService;

    @BeforeEach
    void setUp() {
        consultationService = new ConsultationService(consultationRepository, tokenService, slotService);
    }

    private static TokenResponse token(TokenStatus status) {
        return new TokenResponse(TOKEN_ID, HOSPITAL_ID, PATIENT_ID, SLOT_ID, 3, status, Instant.parse("2026-11-12T04:30:00Z"));
    }

    private static SlotResponse slot() {
        return new SlotResponse(SLOT_ID, HOSPITAL_ID, DOCTOR_ID, LocalDate.of(2026, 11, 13), LocalTime.of(9, 0),
                LocalTime.of(12, 0), 30, 5, 25, SlotStatus.AVAILABLE);
    }

    private static Consultation stored(String notes) {
        Consultation consultation = new Consultation(HOSPITAL_ID, PATIENT_ID, DOCTOR_ID, TOKEN_ID, notes);
        ReflectionTestUtils.setField(consultation, "id", CONSULTATION_ID);
        ReflectionTestUtils.setField(consultation, "startedAt", Instant.parse("2026-11-12T04:35:00Z"));
        return consultation;
    }

    private void aCalledPatientWithNoConsultationYet() {
        when(consultationRepository.findByTokenId(TOKEN_ID)).thenReturn(Optional.empty());
        when(tokenService.get(TOKEN_ID)).thenReturn(token(TokenStatus.IN_CONSULTATION));
        when(slotService.get(SLOT_ID)).thenReturn(slot());
    }

    // ---- start ----

    @Test
    void start_createsTheConsultationWithThePatientHospitalAndTheSessionsDoctor() {
        aCalledPatientWithNoConsultationYet();
        when(consultationRepository.saveAndFlush(any(Consultation.class)))
                .thenAnswer(invocation -> {
                    Consultation saved = invocation.getArgument(0);
                    ReflectionTestUtils.setField(saved, "id", CONSULTATION_ID);
                    return saved;
                });

        StartResult result = consultationService.start(TOKEN_ID, "  Fever for three days  ");

        assertThat(result.created()).isTrue();
        ConsultationResponse response = result.consultation();
        assertThat(response.id()).isEqualTo(CONSULTATION_ID);
        assertThat(response.hospitalId()).isEqualTo(HOSPITAL_ID);
        assertThat(response.patientId()).isEqualTo(PATIENT_ID);
        assertThat(response.doctorId()).isEqualTo(DOCTOR_ID);
        assertThat(response.tokenId()).isEqualTo(TOKEN_ID);
        assertThat(response.notes()).isEqualTo("Fever for three days");
    }

    @Test
    void start_blankNotesAreStoredAsNoNotes() {
        aCalledPatientWithNoConsultationYet();
        when(consultationRepository.saveAndFlush(any(Consultation.class))).thenAnswer(invocation -> invocation.getArgument(0));

        consultationService.start(TOKEN_ID, "   ");

        ArgumentCaptor<Consultation> saved = ArgumentCaptor.forClass(Consultation.class);
        verify(consultationRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getNotes()).isNull();
    }

    @Test
    void start_returnsTheExistingConsultationWithoutLookingAtTheTokenAgain() {
        when(consultationRepository.findByTokenId(TOKEN_ID)).thenReturn(Optional.of(stored("earlier notes")));

        StartResult result = consultationService.start(TOKEN_ID, "ignored");

        assertThat(result.created()).isFalse();
        assertThat(result.consultation().notes()).isEqualTo("earlier notes");
        verifyNoInteractions(tokenService, slotService);
        verify(consultationRepository, never()).saveAndFlush(any(Consultation.class));
    }

    @ParameterizedTest
    @EnumSource(value = TokenStatus.class, names = {"BOOKED", "WAITING", "COMPLETED", "CANCELLED", "NO_SHOW"})
    void start_isRefusedForAPatientWhoIsNotInConsultation(TokenStatus status) {
        when(consultationRepository.findByTokenId(TOKEN_ID)).thenReturn(Optional.empty());
        when(tokenService.get(TOKEN_ID)).thenReturn(token(status));

        assertThatThrownBy(() -> consultationService.start(TOKEN_ID, "notes"))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> {
                    ApiException exception = (ApiException) thrown;
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getCode()).isEqualTo("TOKEN_NOT_IN_CONSULTATION");
                });

        verify(consultationRepository, never()).saveAndFlush(any(Consultation.class));
        verifyNoInteractions(slotService);
    }

    @Test
    void start_unknownTokenThrowsNotFound() {
        when(consultationRepository.findByTokenId(TOKEN_ID)).thenReturn(Optional.empty());
        when(tokenService.get(TOKEN_ID)).thenThrow(ApiException.notFound("Token", TOKEN_ID));

        assertThatThrownBy(() -> consultationService.start(TOKEN_ID, null))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));

        verify(consultationRepository, never()).saveAndFlush(any(Consultation.class));
    }

    @Test
    void start_aSimultaneousRequestThatLostTheRaceGetsTheWinnersConsultation() {
        when(consultationRepository.findByTokenId(TOKEN_ID)).thenReturn(Optional.empty(), Optional.of(stored("winner")));
        when(tokenService.get(TOKEN_ID)).thenReturn(token(TokenStatus.IN_CONSULTATION));
        when(slotService.get(SLOT_ID)).thenReturn(slot());
        when(consultationRepository.saveAndFlush(any(Consultation.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key (token_id)"));

        StartResult result = consultationService.start(TOKEN_ID, "mine");

        assertThat(result.created()).isFalse();
        assertThat(result.consultation().notes()).isEqualTo("winner");
    }

    @Test
    void start_aConflictWithNoWinnerToHandBackIsRethrown() {
        when(consultationRepository.findByTokenId(TOKEN_ID)).thenReturn(Optional.empty(), Optional.empty());
        when(tokenService.get(TOKEN_ID)).thenReturn(token(TokenStatus.IN_CONSULTATION));
        when(slotService.get(SLOT_ID)).thenReturn(slot());
        when(consultationRepository.saveAndFlush(any(Consultation.class)))
                .thenThrow(new DataIntegrityViolationException("foreign key violation"));

        assertThatThrownBy(() -> consultationService.start(TOKEN_ID, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---- get ----

    @Test
    void get_returnsTheConsultationWithItsNotes() {
        when(consultationRepository.findById(CONSULTATION_ID)).thenReturn(Optional.of(stored("some notes")));

        ConsultationResponse response = consultationService.get(CONSULTATION_ID);

        assertThat(response.id()).isEqualTo(CONSULTATION_ID);
        assertThat(response.notes()).isEqualTo("some notes");
        assertThat(response.startedAt()).isEqualTo(Instant.parse("2026-11-12T04:35:00Z"));
    }

    @Test
    void get_unknownConsultationThrowsNotFound() {
        when(consultationRepository.findById(CONSULTATION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> consultationService.get(CONSULTATION_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ---- updateNotes ----

    @Test
    void updateNotes_replacesTheNotesWithTheTrimmedText() {
        Consultation consultation = stored("old");
        when(consultationRepository.findById(CONSULTATION_ID)).thenReturn(Optional.of(consultation));
        when(consultationRepository.saveAndFlush(consultation)).thenReturn(consultation);

        ConsultationResponse response = consultationService.updateNotes(CONSULTATION_ID, "  new notes  ");

        assertThat(response.notes()).isEqualTo("new notes");
        assertThat(consultation.getNotes()).isEqualTo("new notes");
    }

    @Test
    void updateNotes_blankOrNullClearsTheNotes() {
        Consultation consultation = stored("old");
        when(consultationRepository.findById(CONSULTATION_ID)).thenReturn(Optional.of(consultation));
        when(consultationRepository.saveAndFlush(consultation)).thenReturn(consultation);

        assertThat(consultationService.updateNotes(CONSULTATION_ID, "   ").notes()).isNull();
        assertThat(consultationService.updateNotes(CONSULTATION_ID, null).notes()).isNull();
    }

    @Test
    void updateNotes_unknownConsultationThrowsNotFound() {
        when(consultationRepository.findById(CONSULTATION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> consultationService.updateNotes(CONSULTATION_ID, "x"))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));

        verify(consultationRepository, never()).saveAndFlush(any(Consultation.class));
    }
}
