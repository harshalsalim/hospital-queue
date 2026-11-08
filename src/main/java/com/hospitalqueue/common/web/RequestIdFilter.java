package com.hospitalqueue.common.web;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Gives every HTTP request a correlation id (the "request id").
 *
 * <p>The id is taken from the incoming {@code X-Request-Id} header when it looks safe,
 * otherwise a new UUID is generated. It is stored in the logging MDC under {@code requestId}
 * (so every log line of the request carries it), echoed back in the response header, and
 * included in error responses. Support staff can use it to find all log lines of one request.
 *
 * <p>The filter runs before Spring Security so that even rejected requests are traceable.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    /** Name of the HTTP header that carries the request id. */
    public static final String HEADER = "X-Request-Id";

    /** Key used in the logging MDC. */
    public static final String MDC_KEY = "requestId";

    /** Only short letters, digits and dashes are accepted from callers, which prevents log injection. */
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String requestId = (incoming != null && SAFE_ID.matcher(incoming).matches())
                ? incoming
                : UUID.randomUUID().toString();

        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
