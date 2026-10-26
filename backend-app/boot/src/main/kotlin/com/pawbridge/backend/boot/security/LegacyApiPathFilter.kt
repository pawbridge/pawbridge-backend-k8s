package com.pawbridge.backend.boot.security

import jakarta.servlet.FilterChain
import jakarta.servlet.ServletException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import java.io.IOException
import java.nio.charset.StandardCharsets
import org.springframework.http.server.PathContainer
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.UrlPathHelper
import org.springframework.web.util.pattern.PathPattern
import org.springframework.web.util.pattern.PathPatternParser

/**
 * TEMPORARY transition filter: restores the API Gateway `RewritePath` behaviour for the old frontend paths.
 *
 * Requests such as `GET /api/animals/7` are presented to Spring Security and MVC as
 * `GET /api/v1/animals/7`. It must run before the Security filter chain. Only the paths and methods the
 * Gateway routed are rewritten; everything else (for example `/api/carts/reset` or anything under `/internal/`)
 * stays untouched and is denied by the security rules. requests under `/api/v1/` are never changed.
 *
 * Remove this class (and `pawbridge.legacy-api-paths.enabled`) after the frontend calls `/api/v1`.
 * Set the property to false first to verify nothing still uses the old paths. The Gateway's JWT header
 * injection (X-User-*) is intentionally not reproduced: this application verifies the Bearer token itself.
 */
class LegacyApiPathFilter : OncePerRequestFilter() {
    @Throws(ServletException::class, IOException::class)
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val uri = request.requestURI
        val path = PathContainer.parsePath(uri)
        // Decoded path (as Tomcat/Security/MVC see it) so /users/%69nternal/ cannot dodge the refusal.
        val decoded = UrlPathHelper.defaultInstance.getPathWithinApplication(request)
        if (decoded.startsWith("/api/users/internal/") || decoded.startsWith("/api/v1/users/internal/")) {
            // Same refusal the Gateway JWT filter gave service-to-service endpoints.
            response.status = 404
            response.characterEncoding = StandardCharsets.UTF_8.name()
            response.contentType = "application/json"
            response.writer.write("{\"code\":404,\"message\":\"유효하지 않은 엔드포인트입니다.\",\"data\":null}")
            return
        }
        if (uri.startsWith("/api/") && !uri.startsWith("/api/v1/") && routed(path, request.method)) {
            val rewritten = "/api/v1/" + uri.substring("/api/".length)
            request.setAttribute(ORIGINAL_PATH_ATTRIBUTE, uri)
            // servletPath is built from the decoded original so Security matchers and MVC agree on the same path.
            val servletPath = "/api/v1/" + decoded.substring("/api/".length)
            filterChain.doFilter(RewrittenRequest(request, rewritten, servletPath), response)
            return
        }
        filterChain.doFilter(request, response)
    }

    private class Rule(private val patterns: List<PathPattern>, private val methods: Set<String>?) {
        fun matches(path: PathContainer, method: String): Boolean {
            return (methods == null || methods.contains(method)) && patterns.any { it.matches(path) }
        }
    }

    private class RewrittenRequest(
        request: HttpServletRequest,
        private val rewrittenPath: String,
        private val rewrittenServletPath: String,
    ) : HttpServletRequestWrapper(request) {
        override fun getRequestURI(): String = rewrittenPath

        override fun getRequestURL(): StringBuffer {
            val original = super.getRequestURL()
            val start = original.length - super.getRequestURI().length
            return if (start < 0) original else StringBuffer(original.substring(0, start)).append(rewrittenPath)
        }

        override fun getServletPath(): String = rewrittenServletPath

        override fun getPathInfo(): String? = null
    }

    companion object {
        @JvmField
        val ORIGINAL_PATH_ATTRIBUTE: String = LegacyApiPathFilter::class.java.name + ".originalPath"

        private val ANY: Set<String>? = null
        private val READ = setOf("GET", "OPTIONS")
        private val READ_WRITE = setOf("GET", "OPTIONS", "POST", "PUT", "PATCH", "DELETE")
        private val PARSER = PathPatternParser()

        private fun rule(methods: Set<String>?, vararg patterns: String): Rule {
            return Rule(patterns.map { PARSER.parse(it) }, methods)
        }

        /** Mirrors the legacy routes of api-gateway/application.yml; first column is the Gateway route's Path predicate. */
        private val RULES: List<Rule> = listOf(
            rule(ANY, "/api/users/**", "/api/auth/**", "/api/favorites/**"),
            rule(ANY, "/api/payments/**"),
            rule(READ_WRITE, "/api/animals/**", "/api/shelters/**"),
            rule(READ, "/api/places", "/api/places/regions", "/api/places/{contentId}"),
            rule(ANY, "/api/chats", "/api/chats/**"),
            rule(ANY, "/api/notes", "/api/notes/**"),
            rule(setOf("POST", "OPTIONS"), "/api/visits/identity"),
            rule(setOf("GET", "POST", "OPTIONS"), "/api/visits/today"),
            rule(READ, "/api/admin/visits/stats/period"),
            rule(READ, "/api/home/videos"),
            rule(ANY, "/api/admin/videos", "/api/admin/videos/**"),
            rule(ANY, "/api/posts/**", "/api/comments/**", "/api/reports", "/api/reports/**"),
            rule(READ_WRITE, "/api/products/**", "/api/categories/**"),
            rule(setOf("POST", "OPTIONS"), "/api/images", "/api/images/**"),
            rule(READ, "/api/option-groups", "/api/option-groups/{groupId}"),
            rule(setOf("POST", "PUT", "DELETE", "OPTIONS"), "/api/option-groups", "/api/option-groups/**"),
            rule(setOf("GET", "PATCH", "OPTIONS"), "/api/admin/orders", "/api/admin/orders/**"),
            rule(setOf("GET", "DELETE", "OPTIONS"), "/api/carts"),
            rule(setOf("POST", "OPTIONS"), "/api/carts/items"),
            rule(setOf("PATCH", "DELETE", "OPTIONS"), "/api/carts/items/{skuId}"),
            rule(ANY, "/api/orders/**"),
            rule(
                ANY,
                "/api/admin/users",
                "/api/admin/users/**",
                "/api/admin/stats/daily-signups",
                "/api/admin/posts",
                "/api/admin/posts/**",
                "/api/admin/stats/daily-animals",
                "/api/admin/stats/intake-trend",
            ),
        )

        /** The Gateway sent these to a protected route, but the public upload endpoint must not become reachable that way. */
        private val LOST_SEARCH_ALIAS: PathPattern = PARSER.parse("/api/animals/lost-candidates")

        private fun routed(path: PathContainer, method: String): Boolean {
            if ("POST" == method && LOST_SEARCH_ALIAS.matches(path)) {
                return false
            }
            return RULES.any { rule -> rule.matches(path, method) }
        }
    }
}
