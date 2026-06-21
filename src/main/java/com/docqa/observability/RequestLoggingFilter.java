package com.docqa.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Servlet filter that assigns a correlation ID to every request, exposes it in
 * MDC (so it appears in every log line for that request), echoes it back in the
 * response header, and logs a one-line summary when the response is committed.
 *
 * The filter runs before Spring Security so correlation IDs are available in
 * security-layer log output.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
@Slf4j
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String MDC_REQUEST_ID    = "requestId";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        String requestId = resolveRequestId(request);
        MDC.put(MDC_REQUEST_ID, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);

        long startNs = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - startNs) / 1_000_000;
            log.info("{} {} → {} [{}ms]",
                    request.getMethod(),
                    request.getRequestURI(),
                    response.getStatus(),
                    durationMs);
            MDC.remove(MDC_REQUEST_ID);
        }
    }

    private static String resolveRequestId(HttpServletRequest request) {
        String header = request.getHeader(REQUEST_ID_HEADER);
        return (header != null && !header.isBlank()) ? header : UUID.randomUUID().toString();
    }

    /** Skip detailed logging for Actuator probes — they are noisy and add no value. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.startsWith("/actuator/health") || uri.startsWith("/actuator/prometheus");
    }
}
