package com.hospitalqueue.common.exception;

import java.time.Instant;
import java.util.Map;

import org.slf4j.MDC;

import com.hospitalqueue.common.web.RequestIdFilter;

/**
 * The single JSON error format returned by every API.
 *
 * @param code      stable machine-readable error code, for example {@code NOT_FOUND}
 * @param message   short human-readable explanation; never contains patient data or SQL
 * @param timestamp when the error was produced (UTC)
 * @param requestId correlation id of the failed request, matches the {@code X-Request-Id} header
 * @param details   field-level messages for validation errors; empty for other errors
 */
public record ApiError(String code, String message, Instant timestamp, String requestId, Map<String, String> details) {

    /** Creates an error with no field details. */
    public static ApiError of(String code, String message) {
        return of(code, message, Map.of());
    }

    /** Creates an error with field details; the request id is read from the logging MDC. */
    public static ApiError of(String code, String message, Map<String, String> details) {
        return new ApiError(code, message, Instant.now(), MDC.get(RequestIdFilter.MDC_KEY), details);
    }
}
