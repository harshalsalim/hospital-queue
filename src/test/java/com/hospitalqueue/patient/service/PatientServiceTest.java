package com.hospitalqueue.patient.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import com.hospitalqueue.common.exception.ApiException;
import com.hospitalqueue.patient.dto.PatientResponse;
import com.hospitalqueue.patient.dto.RegisterPatientRequest;
import com.hospitalqueue.patient.entity.Patient;
import com.hospitalqueue.patient.repository.PatientRepository;

/** Unit tests for {@link PatientService}: the repository is faked, no database or Spring needed. */
@ExtendWith(MockitoExtension.class)
class PatientServiceTest {

    private static final String NORMALIZED = "+919876543210";

    @Mock
    private PatientRepository patientRepository;

    @InjectMocks
    private PatientService patientService;

    @Test
    void register_savesTheNormalisedNumberAndATrimmedName() {
        when(patientRepository.existsByPhoneNumber(NORMALIZED)).thenReturn(false);
        when(patientRepository.save(any(Patient.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PatientResponse response = patientService.register(new RegisterPatientRequest("98765 43210", "  Anil Kumar  "));

        ArgumentCaptor<Patient> saved = ArgumentCaptor.forClass(Patient.class);
        verify(patientRepository).save(saved.capture());
        assertThat(saved.getValue().getPhoneNumber()).isEqualTo(NORMALIZED);
        assertThat(saved.getValue().getName()).isEqualTo("Anil Kumar");
        assertThat(response.name()).isEqualTo("Anil Kumar");
    }

    @Test
    void register_responseNeverContainsTheFullPhoneNumber() {
        when(patientRepository.existsByPhoneNumber(NORMALIZED)).thenReturn(false);
        when(patientRepository.save(any(Patient.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PatientResponse response = patientService.register(new RegisterPatientRequest("+91 98765 43210", null));

        assertThat(response.maskedPhoneNumber()).isEqualTo("+91******3210");
        assertThat(response.toString()).doesNotContain("9876543210");
    }

    @Test
    void register_aBlankNameIsStoredAsNoName() {
        when(patientRepository.existsByPhoneNumber(NORMALIZED)).thenReturn(false);
        when(patientRepository.save(any(Patient.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PatientResponse response = patientService.register(new RegisterPatientRequest("9876543210", "   "));

        assertThat(response.name()).isNull();
    }

    @Test
    void register_anInvalidNumberThrowsBadRequestAndTouchesNoData() {
        assertThatThrownBy(() -> patientService.register(new RegisterPatientRequest("12345", "Anil")))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> {
                    ApiException exception = (ApiException) thrown;
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getCode()).isEqualTo("INVALID_PHONE_NUMBER");
                    assertThat(exception.getMessage()).doesNotContain("12345");
                });

        verifyNoInteractions(patientRepository);
    }

    @Test
    void register_aNumberInADifferentFormatOfAnExistingPatientIsAConflict() {
        when(patientRepository.existsByPhoneNumber(NORMALIZED)).thenReturn(true);

        assertThatThrownBy(() -> patientService.register(new RegisterPatientRequest("09876543210", "Anil")))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> {
                    ApiException exception = (ApiException) thrown;
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getCode()).isEqualTo("PATIENT_EXISTS");
                    assertThat(exception.getMessage()).doesNotContain("9876543210");
                });

        verify(patientRepository, never()).save(any(Patient.class));
    }

    @Test
    void get_returnsThePatientWithAMaskedNumber() {
        UUID id = UUID.randomUUID();
        when(patientRepository.findById(id)).thenReturn(Optional.of(new Patient(NORMALIZED, "Anil Kumar")));

        PatientResponse response = patientService.get(id);

        assertThat(response.name()).isEqualTo("Anil Kumar");
        assertThat(response.maskedPhoneNumber()).isEqualTo("+91******3210");
    }

    @Test
    void get_throwsNotFoundWhenThePatientDoesNotExist() {
        UUID id = UUID.randomUUID();
        when(patientRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> patientService.get(id))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void exists_delegatesToTheRepository() {
        UUID known = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();
        when(patientRepository.existsById(known)).thenReturn(true);
        when(patientRepository.existsById(unknown)).thenReturn(false);

        assertThat(patientService.exists(known)).isTrue();
        assertThat(patientService.exists(unknown)).isFalse();
    }
}
