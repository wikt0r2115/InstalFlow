package pl.instalflow.identity.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        request.setAttribute("requestId", requestId);
        response.setHeader("X-Request-Id", requestId);
        MDC.put("requestId", requestId);
        boolean completed = false;
        try {
            chain.doFilter(request, response);
            completed = true;
        } finally {
            if (completed) {
                log.info("HTTP {} status={} requestId={}", request.getMethod(), response.getStatus(), requestId);
            } else {
                log.warn("HTTP {} failed requestId={}", request.getMethod(), requestId);
            }
            MDC.remove("requestId");
        }
    }
}
