package com.pawbridge.backend.boot.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.springframework.http.server.PathContainer;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * TEMPORARY transition filter: restores the API Gateway {@code RewritePath} behaviour for the old frontend paths.
 *
 * <p>Requests such as {@code GET /api/animals/7} are presented to Spring Security and MVC as
 * {@code GET /api/v1/animals/7}. It must run before the Security filter chain. Only the paths and methods the
 * Gateway routed are rewritten; everything else (for example {@code /api/carts/reset}, {@code /internal/**})
 * stays untouched and is denied by the security rules. {@code /api/v1/**} requests are never changed.
 *
 * <p>Remove this class (and {@code pawbridge.legacy-api-paths.enabled}) after the frontend calls {@code /api/v1}.
 * Set the property to false first to verify nothing still uses the old paths. The Gateway's JWT header
 * injection (X-User-*) is intentionally not reproduced: this application verifies the Bearer token itself.
 */
public final class LegacyApiPathFilter extends OncePerRequestFilter {
    public static final String ORIGINAL_PATH_ATTRIBUTE = LegacyApiPathFilter.class.getName() + ".originalPath";
    private static final Set<String> ANY = null;
    private static final Set<String> READ = Set.of("GET", "OPTIONS");
    private static final Set<String> WRITE = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> READ_WRITE = Set.of("GET", "OPTIONS", "POST", "PUT", "PATCH", "DELETE");
    private static final PathPatternParser PARSER = new PathPatternParser();

    private record Rule(List<PathPattern> patterns, Set<String> methods) {
        boolean matches(PathContainer path, String method) {
            return (methods == ANY || methods.contains(method)) && patterns.stream().anyMatch(p -> p.matches(path));
        }
    }

    private static Rule rule(Set<String> methods, String... patterns) {
        return new Rule(java.util.Arrays.stream(patterns).map(PARSER::parse).toList(), methods);
    }

    /** Mirrors the legacy routes of api-gateway/application.yml; first column is the Gateway route's Path predicate. */
    private static final List<Rule> RULES = List.of(
            rule(ANY, "/api/users/**", "/api/auth/**", "/api/favorites/**"),
            rule(READ_WRITE, "/api/animals/**", "/api/shelters/**"),
            rule(READ, "/api/places", "/api/places/regions", "/api/places/{contentId}"),
            rule(ANY, "/api/chats", "/api/chats/**"),
            rule(ANY, "/api/notes", "/api/notes/**"),
            rule(Set.of("POST", "OPTIONS"), "/api/visits/identity"),
            rule(Set.of("GET", "POST", "OPTIONS"), "/api/visits/today"),
            rule(READ, "/api/admin/visits/stats/period"),
            rule(READ, "/api/home/videos"),
            rule(ANY, "/api/admin/videos", "/api/admin/videos/**"),
            rule(ANY, "/api/posts/**", "/api/comments/**", "/api/reports", "/api/reports/**"),
            rule(READ_WRITE, "/api/products/**", "/api/categories/**"),
            rule(Set.of("POST", "OPTIONS"), "/api/images", "/api/images/**"),
            rule(READ, "/api/option-groups", "/api/option-groups/{groupId}"),
            rule(Set.of("POST", "PUT", "DELETE", "OPTIONS"), "/api/option-groups", "/api/option-groups/**"),
            rule(Set.of("GET", "PATCH", "OPTIONS"), "/api/admin/orders", "/api/admin/orders/**"),
            rule(Set.of("GET", "DELETE", "OPTIONS"), "/api/carts"),
            rule(Set.of("POST", "OPTIONS"), "/api/carts/items"),
            rule(Set.of("PATCH", "DELETE", "OPTIONS"), "/api/carts/items/{skuId}"),
            rule(ANY, "/api/orders/**"),
            rule(ANY, "/api/admin/users", "/api/admin/users/**", "/api/admin/stats/daily-signups",
                    "/api/admin/posts", "/api/admin/posts/**",
                    "/api/admin/stats/daily-animals", "/api/admin/stats/intake-trend"));
    /** Routed by the Gateway without any rewrite; the application already serves these paths unchanged. */
    private static final PathPattern PAYMENTS = PARSER.parse("/api/payments/**");
    /** The Gateway sent these to a protected route, but the public upload endpoint must not become reachable that way. */
    private static final PathPattern LOST_SEARCH_ALIAS = PARSER.parse("/api/animals/lost-candidates");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();
        var path = PathContainer.parsePath(uri);
        // Decoded path (as Tomcat/Security/MVC see it) so /users/%69nternal/ cannot dodge the refusal.
        String decoded = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        if (decoded.startsWith("/api/users/internal/") || decoded.startsWith("/api/v1/users/internal/")) {
            // Same refusal the Gateway JWT filter gave service-to-service endpoints.
            response.setStatus(404);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":404,\"message\":\"유효하지 않은 엔드포인트입니다.\",\"data\":null}");
            return;
        }
        if (uri.startsWith("/api/") && !uri.startsWith("/api/v1/") && !PAYMENTS.matches(path)
                && routed(path, request.getMethod())) {
            String rewritten = "/api/v1/" + uri.substring("/api/".length());
            request.setAttribute(ORIGINAL_PATH_ATTRIBUTE, uri);
            // servletPath is built from the decoded original so Security matchers and MVC agree on the same path.
            chain.doFilter(new RewrittenRequest(request, rewritten, "/api/v1/" + decoded.substring("/api/".length())), response);
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean routed(PathContainer path, String method) {
        if ("POST".equals(method) && LOST_SEARCH_ALIAS.matches(path)) return false;
        return RULES.stream().anyMatch(rule -> rule.matches(path, method));
    }

    private static final class RewrittenRequest extends HttpServletRequestWrapper {
        private final String path;
        private final String servletPath;

        RewrittenRequest(HttpServletRequest request, String path, String servletPath) {
            super(request);
            this.path = path;
            this.servletPath = servletPath;
        }

        @Override public String getRequestURI() { return path; }

        @Override public StringBuffer getRequestURL() {
            var original = super.getRequestURL();
            int start = original.length() - super.getRequestURI().length();
            return start < 0 ? original : new StringBuffer(original.substring(0, start)).append(path);
        }

        @Override public String getServletPath() { return servletPath; }

        @Override public String getPathInfo() { return null; }
    }
}
