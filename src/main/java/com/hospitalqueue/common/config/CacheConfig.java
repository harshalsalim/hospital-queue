package com.hospitalqueue.common.config;

import java.time.Duration;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;

/**
 * Redis cache setup for read-heavy, rarely-changing data.
 *
 * <ul>
 *   <li>Every entry expires (TTL), so stale data cannot live forever.</li>
 *   <li>Writes and evictions are transaction-aware: they happen only after the database
 *       transaction commits, so a rolled-back change never reaches the cache.</li>
 *   <li>Values are cached as Java-serialized DTO records, so cached classes must implement
 *       Serializable. Entities are never cached.</li>
 *   <li>The serializer uses this class's class loader, which avoids ClassCastException
 *       with Spring DevTools automatic restarts.</li>
 * </ul>
 *
 * <p>If Redis is down, {@link CacheErrorConfig} makes reads fall through to the database.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    private static final Duration DEFAULT_TTL = Duration.ofMinutes(10);
    private static final Duration SPECIALTY_TTL = Duration.ofHours(1);

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(DEFAULT_TTL)
                .disableCachingNullValues()
                .prefixCacheNameWith("hq:")
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
                        RedisSerializer.java(CacheConfig.class.getClassLoader())));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaults)
                .withCacheConfiguration(CacheNames.SPECIALTIES, defaults.entryTtl(SPECIALTY_TTL))
                .transactionAware()
                .build();
    }
}
