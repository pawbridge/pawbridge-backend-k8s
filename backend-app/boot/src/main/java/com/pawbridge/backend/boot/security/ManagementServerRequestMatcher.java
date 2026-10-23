package com.pawbridge.backend.boot.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

/**
 * Matches only requests that arrived on the separate management server (management.server.port).
 *
 * <p>The management port has its own embedded server and servlet context, but Spring Boot registers the same
 * Spring Security filter chains on it. The request's servlet context tells which server it came from, so this
 * does not depend on a configured port number. With no separate management port (or in MockMvc) it never matches.
 */
public final class ManagementServerRequestMatcher implements RequestMatcher {
    private static final String MANAGEMENT_NAMESPACE = "management";

    @Override
    public boolean matches(HttpServletRequest request) {
        WebApplicationContext context = WebApplicationContextUtils.getWebApplicationContext(request.getServletContext());
        return context != null && WebServerApplicationContext.hasServerNamespace(context, MANAGEMENT_NAMESPACE);
    }
}
