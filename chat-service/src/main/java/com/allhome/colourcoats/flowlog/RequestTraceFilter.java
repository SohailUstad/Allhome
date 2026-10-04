package com.allhome.colourcoats.flowlog;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestTraceFilter extends OncePerRequestFilter {
    private final Telemetry telemetry;
    public RequestTraceFilter(ObjectProvider<Telemetry> telemetry) { this.telemetry = telemetry.getIfAvailable(Telemetry::local); }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        try (var scope = TraceContext.attach(new TraceContext())) {
            response.setHeader("X-Request-Id", TraceContext.current().requestId);
            telemetry.event("http.request.received", "method", request.getMethod(), "request_bytes", request.getContentLengthLong());
            boolean failed = false;
            try { chain.doFilter(request, response); }
            catch (IOException | ServletException | RuntimeException e) {
                failed = true;
                telemetry.failure("http.request.failed", e);
                throw e;
            } finally {
                Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                String outcome = MDC.get("business_outcome");
                if (outcome == null) outcome = failed || response.getStatus() >= 500 ? "failed"
                        : response.getStatus() >= 400 ? "rejected" : "completed";
                telemetry.event("http.request.completed", "route", route == null ? "unmatched" : route.toString(),
                        "method", request.getMethod(), "http_status", response.getStatus(), "outcome", outcome,
                        "response_committed", response.isCommitted(), "duration_ms", Telemetry.elapsed(start));
                telemetry.count("app.requests", outcome);
            }
        }
    }
}
