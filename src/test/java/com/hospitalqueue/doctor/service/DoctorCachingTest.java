package com.hospitalqueue.doctor.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import com.hospitalqueue.admin.service.HospitalService;
import com.hospitalqueue.admin.service.SpecialtyService;
import com.hospitalqueue.common.config.CacheNames;
import com.hospitalqueue.common.exception.ApiException;
import com.hospitalqueue.doctor.dto.CreateDoctorRequest;
import com.hospitalqueue.doctor.entity.Doctor;
import com.hospitalqueue.doctor.repository.DoctorRepository;

/**
 * Proves the cache annotations on {@link DoctorService} work: repeated reads hit the repository once,
 * each filter and page is cached separately, a successful create evicts the cache, and a missing
 * doctor is never cached. Uses an in-memory cache, so no Redis, database or Docker is needed.
 */
@SpringJUnitConfig(DoctorCachingTest.CachingTestConfig.class)
class DoctorCachingTest {

    private static final UUID HOSPITAL_ID = UUID.randomUUID();
    private static final UUID SPECIALTY_ID = UUID.randomUUID();

    @Configuration
    @EnableCaching
    static class CachingTestConfig {

        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager(CacheNames.DOCTORS);
        }

        @Bean
        DoctorRepository doctorRepository() {
            return Mockito.mock(DoctorRepository.class);
        }

        @Bean
        HospitalService hospitalService() {
            return Mockito.mock(HospitalService.class);
        }

        @Bean
        SpecialtyService specialtyService() {
            return Mockito.mock(SpecialtyService.class);
        }

        @Bean
        DoctorService doctorService(DoctorRepository doctorRepository,
                                    HospitalService hospitalService,
                                    SpecialtyService specialtyService) {
            return new DoctorService(doctorRepository, hospitalService, specialtyService);
        }
    }

    @Autowired
    private DoctorService doctorService;

    @Autowired
    private DoctorRepository doctorRepository;

    @Autowired
    private HospitalService hospitalService;

    @Autowired
    private SpecialtyService specialtyService;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void resetMocksAndCaches() {
        Mockito.reset(doctorRepository, hospitalService, specialtyService);
        for (String name : cacheManager.getCacheNames()) {
            Cache cache = cacheManager.getCache(name);
            if (cache != null) {
                cache.clear();
            }
        }
    }

    private static Page<Doctor> emptyPage() {
        return new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
    }

    @Test
    void list_isCachedPerFilterAndPage() {
        when(doctorRepository.findAll(any(Pageable.class))).thenReturn(emptyPage());
        when(doctorRepository.findByHospitalId(any(UUID.class), any(Pageable.class))).thenReturn(emptyPage());

        doctorService.list(null, null, 0, 20);
        doctorService.list(null, null, 0, 20);
        verify(doctorRepository, times(1)).findAll(any(Pageable.class));

        doctorService.list(null, null, 1, 20);
        verify(doctorRepository, times(2)).findAll(any(Pageable.class));

        doctorService.list(HOSPITAL_ID, null, 0, 20);
        doctorService.list(HOSPITAL_ID, null, 0, 20);
        verify(doctorRepository, times(1)).findByHospitalId(any(UUID.class), any(Pageable.class));
    }

    @Test
    void get_isServedFromCacheOnTheSecondCall() {
        UUID id = UUID.randomUUID();
        when(doctorRepository.findById(id))
                .thenReturn(Optional.of(new Doctor(HOSPITAL_ID, SPECIALTY_ID, "Dr. Asha Nair", "Council", "KMC1")));

        doctorService.get(id);
        doctorService.get(id);

        verify(doctorRepository, times(1)).findById(id);
    }

    @Test
    void get_notFoundIsNotCached() {
        UUID id = UUID.randomUUID();
        when(doctorRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> doctorService.get(id)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> doctorService.get(id)).isInstanceOf(ApiException.class);

        verify(doctorRepository, times(2)).findById(id);
    }

    @Test
    void create_evictsTheCachedReads() {
        when(doctorRepository.findAll(any(Pageable.class))).thenReturn(emptyPage());
        when(hospitalService.exists(HOSPITAL_ID)).thenReturn(true);
        when(specialtyService.exists(SPECIALTY_ID)).thenReturn(true);
        when(doctorRepository.existsByRegistrationCouncilIgnoreCaseAndRegistrationNumberIgnoreCase("Council", "KMC1"))
                .thenReturn(false);
        when(doctorRepository.save(any(Doctor.class))).thenAnswer(invocation -> invocation.getArgument(0));

        doctorService.list(null, null, 0, 20);
        doctorService.create(HOSPITAL_ID, new CreateDoctorRequest(SPECIALTY_ID, "Dr. Asha Nair", "Council", "KMC1"));
        doctorService.list(null, null, 0, 20);

        verify(doctorRepository, times(2)).findAll(any(Pageable.class));
    }
}
