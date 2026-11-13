package com.hospitalqueue.common.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the application clock. Business rules about "today" and "now" (for example, a session
 * cannot be created in the past) use this clock instead of {@code LocalDate.now()}, so they follow
 * the hospitals' time zone and tests can replace it with a fixed clock.
 */
@Configuration
public class ClockConfig {

    /** Hospitals are in Kerala, so business dates and times are in Indian Standard Time. */
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");

    @Bean
    public Clock clock() {
        return Clock.system(BUSINESS_ZONE);
    }
}
