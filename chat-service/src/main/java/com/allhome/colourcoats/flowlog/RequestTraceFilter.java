package com.allhome.colourcoats.flowlog;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Gives every HTTP request a request id (also returned as X-Request-Id) that all its flow-log entries carry. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestTraceFilter extends OncePerRequestFilter {

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try (var scope = TraceContext.attach(new TraceContext())) {
            response.setHeader("X-Request-Id", TraceContext.current().requestId);
            chain.doFilter(request, response);
        }
    }
}
