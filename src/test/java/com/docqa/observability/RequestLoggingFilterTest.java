package com.docqa.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RequestLoggingFilterTest {

    private RequestLoggingFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RequestLoggingFilter();
    }

    @Test
    void doFilter_setsRequestIdHeader() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/qa/ask");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getHeader("X-Request-Id")).isNotBlank();
        verify(chain).doFilter(request, response);
    }

    @Test
    void doFilter_usesExistingRequestIdHeader() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/qa/ask");
        request.addHeader("X-Request-Id", "my-correlation-id");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getHeader("X-Request-Id")).isEqualTo("my-correlation-id");
    }

    @Test
    void doFilter_generatesNewIdWhenHeaderAbsent() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/documents");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        String id = response.getHeader("X-Request-Id");
        assertThat(id).isNotBlank();
        // generated IDs are UUIDs
        assertThat(id).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    void shouldNotFilter_actuatorHealth_returnsTrue() throws ServletException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health/liveness");

        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    void shouldNotFilter_actuatorPrometheus_returnsTrue() throws ServletException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/prometheus");

        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    void shouldNotFilter_regularEndpoint_returnsFalse() throws ServletException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/qa/ask");

        assertThat(filter.shouldNotFilter(request)).isFalse();
    }
}
