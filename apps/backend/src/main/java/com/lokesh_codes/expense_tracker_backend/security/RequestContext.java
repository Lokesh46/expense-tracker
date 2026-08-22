package com.lokesh_codes.expense_tracker_backend.security;

import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Where a request came from, when there is a request.
 *
 * <p>Read from {@link RequestContextHolder} rather than by injecting a
 * request-scoped {@code HttpServletRequest}, because the same audit code runs
 * from the nightly scheduler and from startup, where no request exists. A proxy
 * would throw there; this returns null.
 */
@Component
public class RequestContext {

    private static final int MAX_USER_AGENT = 300;

    /**
     * The client's address.
     *
     * <p>Behind Render's load balancer {@code getRemoteAddr()} is the balancer,
     * not the caller, so {@code X-Forwarded-For} is preferred and its first
     * entry taken — that is the original client; the rest are the proxies it
     * passed through.
     *
     * <p>The header is client-controllable and so cannot be trusted for access
     * control. It is only ever displayed, never used to decide anything.
     */
    public String ipAddress() {
        HttpServletRequest request = current();
        if (request == null) {
            return null;
        }

        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            if (!first.isEmpty()) {
                return truncate(first, 60);
            }
        }
        return truncate(request.getRemoteAddr(), 60);
    }

    public String userAgent() {
        HttpServletRequest request = current();
        return request == null ? null : truncate(request.getHeader("User-Agent"), MAX_USER_AGENT);
    }

    private HttpServletRequest current() {
        var attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servlet ? servlet.getRequest() : null;
    }

    /** The column is bounded; an over-long header should be trimmed, not rejected. */
    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
