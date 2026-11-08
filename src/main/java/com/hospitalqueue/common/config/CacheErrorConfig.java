package com.hospitalqueue.common.config;

import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.LoggingCacheErrorHandler;
import org.springframework.context.annotation.Configuration;

/**
 * Makes the cache optional at runtime: if Redis is slow or down, cache errors are logged
 * (without stack traces) and swallowed, so the request is answered from the database
 * instead of failing. Kept in its own class so it has no dependencies and starts early.
 */
@Configuration
public class CacheErrorConfig implements CachingConfigurer {

    @Override
    public CacheErrorHandler errorHandler() {
        return new LoggingCacheErrorHandler(false);
    }
}
