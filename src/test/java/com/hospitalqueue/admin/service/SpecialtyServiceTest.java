package com.hospitalqueue.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import com.hospitalqueue.admin.dto.CreateSpecialtyRequest;
import com.hospitalqueue.admin.dto.SpecialtyResponse;
import com.hospitalqueue.admin.entity.Specialty;
import com.hospitalqueue.admin.repository.SpecialtyRepository;
import com.hospitalqueue.common.exception.ApiException;

/** Unit tests for {@link SpecialtyService}: the repository is faked, no database or Spring needed. */
@ExtendWith(MockitoExtension.class)
class SpecialtyServiceTest {

    @Mock
    private SpecialtyRepository specialtyRepository;

    @InjectMocks
    private SpecialtyService specialtyService;

    @Test
    void create_checksAndSavesTheTrimmedName() {
        when(specialtyRepository.existsByNameIgnoreCase("Cardiology")).thenReturn(false);
        when(specialtyRepository.save(any(Specialty.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SpecialtyResponse response = specialtyService.create(new CreateSpecialtyRequest("  Cardiology  "));

        ArgumentCaptor<Specialty> saved = ArgumentCaptor.forClass(Specialty.class);
        verify(specialtyRepository).save(saved.capture());
        assertThat(saved.getValue().getName()).isEqualTo("Cardiology");
        assertThat(response.name()).isEqualTo("Cardiology");
    }

    @Test
    void create_throwsConflictAndSavesNothingWhenTheNameAlreadyExists() {
        when(specialtyRepository.existsByNameIgnoreCase("Cardiology")).thenReturn(true);

        assertThatThrownBy(() -> specialtyService.create(new CreateSpecialtyRequest(" Cardiology ")))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> {
                    ApiException exception = (ApiException) thrown;
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getCode()).isEqualTo("SPECIALTY_EXISTS");
                });

        verify(specialtyRepository, never()).save(any(Specialty.class));
    }

    @Test
    void list_mapsEverySpecialtyInRepositoryOrder() {
        when(specialtyRepository.findAllByOrderByNameAsc())
                .thenReturn(List.of(new Specialty("Cardiology"), new Specialty("Neurology")));

        List<SpecialtyResponse> result = specialtyService.list();

        assertThat(result).extracting(SpecialtyResponse::name).containsExactly("Cardiology", "Neurology");
    }

    @Test
    void exists_delegatesToTheRepository() {
        UUID known = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();
        when(specialtyRepository.existsById(known)).thenReturn(true);
        when(specialtyRepository.existsById(unknown)).thenReturn(false);

        assertThat(specialtyService.exists(known)).isTrue();
        assertThat(specialtyService.exists(unknown)).isFalse();
    }
}
