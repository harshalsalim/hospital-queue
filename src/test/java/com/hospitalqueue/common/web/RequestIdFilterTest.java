package com.hospitalqueue.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;

/** Unit tests for {@link RequestIdFilter}, using Spring's mock servlet objects (no server). */
class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void generatesANewUuidWhenTheRequestHasNoId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> idSeenInsideTheChain = new AtomicReference<>();
        FilterChain chain = (req, res) -> idSeenInsideTheChain.set(MDC.get(RequestIdFilter.MDC_KEY));

        filter.doFilter(request, response, chain);

        String responseId = response.getHeader(RequestIdFilter.HEADER);
        assertThat(responseId).isNotBlank();
        assertThat(UUID.fromString(responseId)).isNotNull();
        assertThat(idSeenInsideTheChain.get()).isEqualTo(responseId);
    }

    @Test
    void keepsAnIncomingIdThatLooksSafe() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER, "client-request-12345");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> idSeenInsideTheChain = new AtomicReference<>();
        FilterChain chain = (req, res) -> idSeenInsideTheChain.set(MDC.get(RequestIdFilter.MDC_KEY));

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("client-request-12345");
        assertThat(idSeenInsideTheChain.get()).isEqualTo("client-request-12345");
    }

    @Test
    void replacesAnIncomingIdThatCouldInjectIntoLogs() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER, "fake id\nINJECTED LOG LINE");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> { };

        filter.doFilter(request, response, chain);

        String responseId = response.getHeader(RequestIdFilter.HEADER);
        assertThat(responseId).doesNotContain("INJECTED").doesNotContain("\n");
        assertThat(UUID.fromString(responseId)).isNotNull();
    }

    @Test
    void replacesAnIncomingIdThatIsTooShort() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER, "abc");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> { };

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(RequestIdFilter.HEADER)).isNotEqualTo("abc");
    }

    @Test
    void clearsTheLoggingContextAfterTheRequestEvenWhenTheChainFails() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
            throw new ServletException("boom");
        };

        assertThatThrownBy(() -> filter.doFilter(request, response, chain)).isInstanceOf(ServletException.class);

        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }
}
