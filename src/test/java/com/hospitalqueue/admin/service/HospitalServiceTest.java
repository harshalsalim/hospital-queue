package com.hospitalqueue.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

import com.hospitalqueue.admin.dto.CreateHospitalRequest;
import com.hospitalqueue.admin.dto.HospitalResponse;
import com.hospitalqueue.admin.entity.Hospital;
import com.hospitalqueue.admin.repository.HospitalRepository;
import com.hospitalqueue.common.dto.PageResponse;
import com.hospitalqueue.common.exception.ApiException;

/** Unit tests for {@link HospitalService}: the repository is faked, no database or Spring needed. */
@ExtendWith(MockitoExtension.class)
class HospitalServiceTest {

    @Mock
    private HospitalRepository hospitalRepository;

    @InjectMocks
    private HospitalService hospitalService;

    @Test
    void create_checksAndSavesTheTrimmedNameAndAddress() {
        when(hospitalRepository.existsByNameIgnoreCaseAndAddressIgnoreCase("City Hospital", "MG Road, Kochi"))
                .thenReturn(false);
        when(hospitalRepository.save(any(Hospital.class))).thenAnswer(invocation -> invocation.getArgument(0));

        HospitalResponse response = hospitalService.create(
                new CreateHospitalRequest("  City Hospital  ", "  MG Road, Kochi  "));

        ArgumentCaptor<Hospital> saved = ArgumentCaptor.forClass(Hospital.class);
        verify(hospitalRepository).save(saved.capture());
        assertThat(saved.getValue().getName()).isEqualTo("City Hospital");
        assertThat(saved.getValue().getAddress()).isEqualTo("MG Road, Kochi");
        assertThat(response.name()).isEqualTo("City Hospital");
        assertThat(response.address()).isEqualTo("MG Road, Kochi");
    }

    @Test
    void create_throwsConflictAndSavesNothingWhenTheSameNameAndAddressExist() {
        when(hospitalRepository.existsByNameIgnoreCaseAndAddressIgnoreCase("City Hospital", "MG Road, Kochi"))
                .thenReturn(true);

        assertThatThrownBy(() -> hospitalService.create(
                new CreateHospitalRequest(" City Hospital ", " MG Road, Kochi ")))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> {
                    ApiException exception = (ApiException) thrown;
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getCode()).isEqualTo("HOSPITAL_EXISTS");
                });

        verify(hospitalRepository, never()).save(any(Hospital.class));
    }

    @Test
    void create_allowsTheSameNameAtADifferentAddress() {
        when(hospitalRepository.existsByNameIgnoreCaseAndAddressIgnoreCase("City Hospital", "Calicut Road, Thrissur"))
                .thenReturn(false);
        when(hospitalRepository.save(any(Hospital.class))).thenAnswer(invocation -> invocation.getArgument(0));

        HospitalResponse response = hospitalService.create(
                new CreateHospitalRequest("City Hospital", "Calicut Road, Thrissur"));

        assertThat(response.address()).isEqualTo("Calicut Road, Thrissur");
        verify(hospitalRepository).save(any(Hospital.class));
    }

    @Test
    void list_requestsTheGivenPageSortedByNameThenId_andMapsPageMetadata() {
        Page<Hospital> page = new PageImpl<>(
                List.of(new Hospital("A Hospital", "Address A"), new Hospital("B Hospital", "Address B")),
                PageRequest.of(1, 2),
                5);
        when(hospitalRepository.findAll(any(Pageable.class))).thenReturn(page);

        PageResponse<HospitalResponse> response = hospitalService.list(1, 2);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(hospitalRepository).findAll(pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(2);
        assertThat(pageable.getValue().getSort().getOrderFor("name")).isNotNull();
        assertThat(pageable.getValue().getSort().getOrderFor("id")).isNotNull();

        assertThat(response.content()).extracting(HospitalResponse::name)
                .containsExactly("A Hospital", "B Hospital");
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(2);
        assertThat(response.totalElements()).isEqualTo(5);
        assertThat(response.totalPages()).isEqualTo(3);
    }

    @Test
    void get_returnsTheHospitalWhenItExists() {
        UUID id = UUID.randomUUID();
        when(hospitalRepository.findById(id)).thenReturn(Optional.of(new Hospital("City Hospital", "MG Road")));

        HospitalResponse response = hospitalService.get(id);

        assertThat(response.name()).isEqualTo("City Hospital");
        assertThat(response.address()).isEqualTo("MG Road");
    }

    @Test
    void get_throwsNotFoundWhenTheHospitalDoesNotExist() {
        UUID id = UUID.randomUUID();
        when(hospitalRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> hospitalService.get(id))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> {
                    ApiException exception = (ApiException) thrown;
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(exception.getCode()).isEqualTo("NOT_FOUND");
                });
    }

    @Test
    void exists_delegatesToTheRepository() {
        UUID known = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();
        when(hospitalRepository.existsById(known)).thenReturn(true);
        when(hospitalRepository.existsById(unknown)).thenReturn(false);

        assertThat(hospitalService.exists(known)).isTrue();
        assertThat(hospitalService.exists(unknown)).isFalse();
    }
}
