package com.hospitalqueue.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import com.hospitalqueue.admin.dto.CreateHospitalRequest;
import com.hospitalqueue.admin.dto.HospitalResponse;
import com.hospitalqueue.admin.entity.Hospital;
import com.hospitalqueue.common.config.CacheErrorConfig;
import com.hospitalqueue.common.config.CacheNames;

/**
 * Simulates Redis being down: every cache operation throws. The services must keep working by
 * reading from the database, because {@link CacheErrorConfig} logs and swallows cache errors.
 */
@SpringJUnitConfig(CacheFailureFallbackTest.BrokenCacheConfig.class)
class CacheFailureFallbackTest {

    /** A cache whose every operation fails, like a dead Redis connection. */
    static class FailingCache extends ConcurrentMapCache {

        FailingCache(String name) {
            super(name);
        }

        @Override
        protected Object lookup(Object key) {
            throw new IllegalStateException("redis is down (read)");
        }

        @Override
        public void put(Object key, Object value) {
            throw new IllegalStateException("redis is down (write)");
        }

        @Override
        public void clear() {
            throw new IllegalStateException("redis is down (clear)");
        }
    }

    @Configuration
    @EnableCaching
    @Import(CacheErrorConfig.class)
    static class BrokenCacheConfig {

        @Bean
        SimpleCacheManager cacheManager() {
            SimpleCacheManager manager = new SimpleCacheManager();
            manager.setCaches(List.of(new FailingCache(CacheNames.HOSPITALS)));
            return manager;
        }

        @Bean
        HospitalRepository hospitalRepository() {
            return Mockito.mock(HospitalRepository.class);
        }

        @Bean
        HospitalService hospitalService(HospitalRepository hospitalRepository) {
            return new HospitalService(hospitalRepository);
        }
    }

    @Autowired
    private HospitalService hospitalService;

    @Autowired
    private HospitalRepository hospitalRepository;

    @BeforeEach
    void resetMocks() {
        Mockito.reset(hospitalRepository);
    }

    @Test
    void read_stillSucceedsFromTheDatabaseWhenTheCacheIsBroken() {
        UUID id = UUID.randomUUID();
        when(hospitalRepository.findById(id)).thenReturn(Optional.of(new Hospital("City Hospital", "MG Road")));

        HospitalResponse first = hospitalService.get(id);
        HospitalResponse second = hospitalService.get(id);

        assertThat(first.name()).isEqualTo("City Hospital");
        assertThat(second.name()).isEqualTo("City Hospital");
        verify(hospitalRepository, times(2)).findById(id);
    }

    @Test
    void create_stillSucceedsWhenTheCacheEvictionFails() {
        when(hospitalRepository.save(any(Hospital.class))).thenAnswer(invocation -> invocation.getArgument(0));

        HospitalResponse response = hospitalService.create(new CreateHospitalRequest("New Hospital", "New Address"));

        assertThat(response.name()).isEqualTo("New Hospital");
    }
}
