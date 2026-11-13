package com.hospitalqueue.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.hospitalqueue.admin.repository.HospitalRepository;
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

import com.hospitalqueue.admin.dto.CreateHospitalRequest;
import com.hospitalqueue.admin.dto.CreateSpecialtyRequest;
import com.hospitalqueue.admin.entity.Hospital;
import com.hospitalqueue.admin.entity.Specialty;
import com.hospitalqueue.admin.repository.SpecialtyRepository;
import com.hospitalqueue.common.config.CacheNames;
import com.hospitalqueue.common.exception.ApiException;

/**
 * Proves the cache annotations on the services really work: repeated reads hit the repository
 * once, a successful create evicts the cache, and failures are never cached or evicted.
 * Uses a tiny Spring context with an in-memory cache, so no Redis, database or Docker is needed.
 */
@SpringJUnitConfig(AdminCachingTest.CachingTestConfig.class)
class AdminCachingTest {

    @Configuration
    @EnableCaching
    static class CachingTestConfig {

        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager(CacheNames.HOSPITALS, CacheNames.SPECIALTIES);
        }

        @Bean
        HospitalRepository hospitalRepository() {
            return Mockito.mock(HospitalRepository.class);
        }

        @Bean
        SpecialtyRepository specialtyRepository() {
            return Mockito.mock(SpecialtyRepository.class);
        }

        @Bean
        HospitalService hospitalService(HospitalRepository hospitalRepository) {
            return new HospitalService(hospitalRepository);
        }

        @Bean
        SpecialtyService specialtyService(SpecialtyRepository specialtyRepository) {
            return new SpecialtyService(specialtyRepository);
        }
    }

    @Autowired
    private HospitalService hospitalService;

    @Autowired
    private SpecialtyService specialtyService;

    @Autowired
    private HospitalRepository hospitalRepository;

    @Autowired
    private SpecialtyRepository specialtyRepository;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void resetMocksAndCaches() {
        Mockito.reset(hospitalRepository, specialtyRepository);
        for (String name : cacheManager.getCacheNames()) {
            Cache cache = cacheManager.getCache(name);
            if (cache != null) {
                cache.clear();
            }
        }
    }

    // ---- hospitals ----

    @Test
    void hospitalGet_isServedFromCacheOnTheSecondCall() {
        UUID id = UUID.randomUUID();
        when(hospitalRepository.findById(id)).thenReturn(Optional.of(new Hospital("City Hospital", "MG Road")));

        hospitalService.get(id);
        hospitalService.get(id);

        verify(hospitalRepository, times(1)).findById(id);
    }

    @Test
    void hospitalGet_notFoundIsNotCached() {
        UUID id = UUID.randomUUID();
        when(hospitalRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> hospitalService.get(id)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> hospitalService.get(id)).isInstanceOf(ApiException.class);

        verify(hospitalRepository, times(2)).findById(id);
    }

    @Test
    void hospitalList_isCachedSeparatelyPerPageAndSize() {
        Page<Hospital> emptyPage = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        when(hospitalRepository.findAll(any(Pageable.class))).thenReturn(emptyPage);

        hospitalService.list(0, 20);
        hospitalService.list(0, 20);
        assertThat(cacheManager.getCache(CacheNames.HOSPITALS)).isNotNull();
        verify(hospitalRepository, times(1)).findAll(any(Pageable.class));

        hospitalService.list(1, 20);
        verify(hospitalRepository, times(2)).findAll(any(Pageable.class));
    }

    @Test
    void hospitalCreate_evictsCachedReads() {
        UUID id = UUID.randomUUID();
        when(hospitalRepository.findById(id)).thenReturn(Optional.of(new Hospital("City Hospital", "MG Road")));
        when(hospitalRepository.save(any(Hospital.class))).thenAnswer(invocation -> invocation.getArgument(0));

        hospitalService.get(id);
        hospitalService.create(new CreateHospitalRequest("New Hospital", "New Address"));
        hospitalService.get(id);

        verify(hospitalRepository, times(2)).findById(id);
    }

    // ---- specialties ----

    @Test
    void specialtyList_isServedFromCacheOnTheSecondCall() {
        when(specialtyRepository.findAllByOrderByNameAsc()).thenReturn(List.of(new Specialty("Cardiology")));

        specialtyService.list();
        specialtyService.list();

        verify(specialtyRepository, times(1)).findAllByOrderByNameAsc();
    }

    @Test
    void specialtyCreate_evictsTheCachedList() {
        when(specialtyRepository.findAllByOrderByNameAsc()).thenReturn(List.of(new Specialty("Cardiology")));
        when(specialtyRepository.existsByNameIgnoreCase("Neurology")).thenReturn(false);
        when(specialtyRepository.save(any(Specialty.class))).thenAnswer(invocation -> invocation.getArgument(0));

        specialtyService.list();
        specialtyService.create(new CreateSpecialtyRequest("Neurology"));
        specialtyService.list();

        verify(specialtyRepository, times(2)).findAllByOrderByNameAsc();
    }

    @Test
    void specialtyCreate_thatFailsDoesNotEvictTheCache() {
        when(specialtyRepository.findAllByOrderByNameAsc()).thenReturn(List.of(new Specialty("Cardiology")));
        when(specialtyRepository.existsByNameIgnoreCase("Cardiology")).thenReturn(true);

        specialtyService.list();
        assertThatThrownBy(() -> specialtyService.create(new CreateSpecialtyRequest("Cardiology")))
                .isInstanceOf(ApiException.class);
        specialtyService.list();

        verify(specialtyRepository, times(1)).findAllByOrderByNameAsc();
    }
}
