package com.hospitalqueue.doctor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

import com.hospitalqueue.admin.service.HospitalService;
import com.hospitalqueue.admin.service.SpecialtyService;
import com.hospitalqueue.common.dto.PageResponse;
import com.hospitalqueue.common.exception.ApiException;
import com.hospitalqueue.doctor.dto.CreateDoctorRequest;
import com.hospitalqueue.doctor.dto.DoctorResponse;
import com.hospitalqueue.doctor.entity.Doctor;
import com.hospitalqueue.doctor.repository.DoctorRepository;

/** Unit tests for {@link DoctorService}: repository and other modules' services are faked. */
@ExtendWith(MockitoExtension.class)
class DoctorServiceTest {

    private static final UUID HOSPITAL_ID = UUID.randomUUID();
    private static final UUID SPECIALTY_ID = UUID.randomUUID();
    private static final String COUNCIL = "Kerala Medical Council";

    @Mock
    private DoctorRepository doctorRepository;

    @Mock
    private HospitalService hospitalService;

    @Mock
    private SpecialtyService specialtyService;

    @InjectMocks
    private DoctorService doctorService;

    private static Page<Doctor> onePage() {
        return new PageImpl<>(List.of(new Doctor(HOSPITAL_ID, SPECIALTY_ID, "Dr. Asha Nair", COUNCIL, "KMC1")),
                PageRequest.of(0, 20), 1);
    }

    // ---- create ----

    @Test
    void create_savesATrimmedDoctorForAnExistingHospitalAndSpecialty() {
        when(hospitalService.exists(HOSPITAL_ID)).thenReturn(true);
        when(specialtyService.exists(SPECIALTY_ID)).thenReturn(true);
        when(doctorRepository.existsByRegistrationCouncilIgnoreCaseAndRegistrationNumberIgnoreCase(COUNCIL, "KMC12345"))
                .thenReturn(false);
        when(doctorRepository.save(any(Doctor.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DoctorResponse response = doctorService.create(HOSPITAL_ID,
                new CreateDoctorRequest(SPECIALTY_ID, "  Dr. Asha Nair  ", "  " + COUNCIL + "  ", "  KMC12345  "));

        ArgumentCaptor<Doctor> saved = ArgumentCaptor.forClass(Doctor.class);
        verify(doctorRepository).save(saved.capture());
        assertThat(saved.getValue().getHospitalId()).isEqualTo(HOSPITAL_ID);
        assertThat(saved.getValue().getSpecialtyId()).isEqualTo(SPECIALTY_ID);
        assertThat(saved.getValue().getName()).isEqualTo("Dr. Asha Nair");
        assertThat(saved.getValue().getRegistrationCouncil()).isEqualTo(COUNCIL);
        assertThat(saved.getValue().getRegistrationNumber()).isEqualTo("KMC12345");
        assertThat(response.name()).isEqualTo("Dr. Asha Nair");
        assertThat(response.hospitalId()).isEqualTo(HOSPITAL_ID);
    }

    @Test
    void create_throwsNotFoundForAnUnknownHospitalAndSavesNothing() {
        when(hospitalService.exists(HOSPITAL_ID)).thenReturn(false);

        assertThatThrownBy(() -> doctorService.create(HOSPITAL_ID,
                new CreateDoctorRequest(SPECIALTY_ID, "Dr. Asha Nair", COUNCIL, "KMC1")))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> {
                    ApiException exception = (ApiException) thrown;
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(exception.getCode()).isEqualTo("NOT_FOUND");
                });

        verifyNoInteractions(specialtyService);
        verify(doctorRepository, never()).save(any(Doctor.class));
    }

    @Test
    void create_throwsBadRequestForAnUnknownSpecialtyAndSavesNothing() {
        when(hospitalService.exists(HOSPITAL_ID)).thenReturn(true);
        when(specialtyService.exists(SPECIALTY_ID)).thenReturn(false);

        assertThatThrownBy(() -> doctorService.create(HOSPITAL_ID,
                new CreateDoctorRequest(SPECIALTY_ID, "Dr. Asha Nair", COUNCIL, "KMC1")))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> {
                    ApiException exception = (ApiException) thrown;
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getCode()).isEqualTo("INVALID_SPECIALTY");
                });

        verify(doctorRepository, never()).save(any(Doctor.class));
    }

    @Test
    void create_throwsConflictForADuplicateRegistrationAndSavesNothing() {
        when(hospitalService.exists(HOSPITAL_ID)).thenReturn(true);
        when(specialtyService.exists(SPECIALTY_ID)).thenReturn(true);
        when(doctorRepository.existsByRegistrationCouncilIgnoreCaseAndRegistrationNumberIgnoreCase(COUNCIL, "KMC1"))
                .thenReturn(true);

        assertThatThrownBy(() -> doctorService.create(HOSPITAL_ID,
                new CreateDoctorRequest(SPECIALTY_ID, "Dr. Asha Nair", COUNCIL, "KMC1")))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> {
                    ApiException exception = (ApiException) thrown;
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getCode()).isEqualTo("DOCTOR_REGISTRATION_EXISTS");
                    assertThat(exception.getMessage()).doesNotContain("KMC1");
                });

        verify(doctorRepository, never()).save(any(Doctor.class));
    }

    // ---- list: each filter combination uses the right repository query ----

    @Test
    void list_withoutFiltersPagesThroughAllDoctorsSortedByNameThenId() {
        when(doctorRepository.findAll(any(Pageable.class))).thenReturn(onePage());

        PageResponse<DoctorResponse> response = doctorService.list(null, null, 0, 20);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(doctorRepository).findAll(pageable.capture());
        verifyNoMoreInteractions(doctorRepository);
        assertThat(pageable.getValue().getPageNumber()).isZero();
        assertThat(pageable.getValue().getPageSize()).isEqualTo(20);
        assertThat(pageable.getValue().getSort().getOrderFor("name")).isNotNull();
        assertThat(pageable.getValue().getSort().getOrderFor("id")).isNotNull();
        assertThat(response.content()).extracting(DoctorResponse::name).containsExactly("Dr. Asha Nair");
        assertThat(response.totalElements()).isEqualTo(1);
    }

    @Test
    void list_filteredByHospitalOnlyUsesTheHospitalQuery() {
        when(doctorRepository.findByHospitalId(eq(HOSPITAL_ID), any(Pageable.class))).thenReturn(onePage());

        doctorService.list(HOSPITAL_ID, null, 0, 20);

        verify(doctorRepository).findByHospitalId(eq(HOSPITAL_ID), any(Pageable.class));
        verifyNoMoreInteractions(doctorRepository);
    }

    @Test
    void list_filteredBySpecialtyOnlyUsesTheSpecialtyQuery() {
        when(doctorRepository.findBySpecialtyId(eq(SPECIALTY_ID), any(Pageable.class))).thenReturn(onePage());

        doctorService.list(null, SPECIALTY_ID, 0, 20);

        verify(doctorRepository).findBySpecialtyId(eq(SPECIALTY_ID), any(Pageable.class));
        verifyNoMoreInteractions(doctorRepository);
    }

    @Test
    void list_filteredByBothUsesTheCombinedQuery() {
        when(doctorRepository.findByHospitalIdAndSpecialtyId(eq(HOSPITAL_ID), eq(SPECIALTY_ID), any(Pageable.class)))
                .thenReturn(onePage());

        doctorService.list(HOSPITAL_ID, SPECIALTY_ID, 0, 20);

        verify(doctorRepository).findByHospitalIdAndSpecialtyId(eq(HOSPITAL_ID), eq(SPECIALTY_ID), any(Pageable.class));
        verifyNoMoreInteractions(doctorRepository);
    }

    // ---- get / exists ----

    @Test
    void get_returnsTheDoctorWhenItExists() {
        UUID id = UUID.randomUUID();
        when(doctorRepository.findById(id))
                .thenReturn(Optional.of(new Doctor(HOSPITAL_ID, SPECIALTY_ID, "Dr. Asha Nair", COUNCIL, "KMC1")));

        DoctorResponse response = doctorService.get(id);

        assertThat(response.name()).isEqualTo("Dr. Asha Nair");
        assertThat(response.registrationNumber()).isEqualTo("KMC1");
    }

    @Test
    void get_throwsNotFoundWhenTheDoctorDoesNotExist() {
        UUID id = UUID.randomUUID();
        when(doctorRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> doctorService.get(id))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void exists_delegatesToTheRepository() {
        UUID known = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();
        when(doctorRepository.existsById(known)).thenReturn(true);
        when(doctorRepository.existsById(unknown)).thenReturn(false);

        assertThat(doctorService.exists(known)).isTrue();
        assertThat(doctorService.exists(unknown)).isFalse();
    }
}
