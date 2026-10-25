package com.pawbridge.backend.boot.security

import com.pawbridge.backend.boot.javaSplit
import com.pawbridge.backend.boot.javaTrim
import java.io.IOException
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.DefaultCorsProcessor
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import org.springframework.web.filter.CorsFilter

/**
 * CORS policy moved from the API Gateway `globalcors` block (api-gateway application.yml, applied to every path).
 * Origins default to the original list and can be replaced with `pawbridge.cors.allowed-origin-patterns`.
 */
object GatewayCors {
    /** Comma separated copy of the Gateway allowedOriginPatterns. */
    const val DEFAULT_ORIGIN_PATTERNS: String = "http://localhost:3000,http://127.0.0.1:3000," +
        "http://localhost:5173,http://127.0.0.1:5173,https://pawbridge.kr,https://www.pawbridge.kr,https://*.pawbridge.kr"

    /** Property that replaces [DEFAULT_ORIGIN_PATTERNS]. */
    const val ORIGIN_PATTERNS_PROPERTY: String = "pawbridge.cors.allowed-origin-patterns"

    /**
     * `@Value` expression every SecurityFilterChain uses to read the origin patterns, so the main chain,
     * the local login chain and the Google OAuth chain always share one property and one default.
     */
    const val ORIGIN_PATTERNS_VALUE: String = "\${" + ORIGIN_PATTERNS_PROPERTY + ":" + DEFAULT_ORIGIN_PATTERNS + "}"

    @JvmStatic
    fun source(originPatternsCsv: String): CorsConfigurationSource {
        val configuration = CorsConfiguration()
        configuration.setAllowedOriginPatterns(
            javaSplit(originPatternsCsv, ',').map { javaTrim(it) }.filter { value -> value.isNotEmpty() },
        )
        configuration.setAllowedMethods(listOf("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"))
        configuration.setAllowedHeaders(listOf("Authorization", "Content-Type", "Accept", "x-user-id"))
        configuration.setExposedHeaders(listOf("Retry-After", "Authorization"))
        configuration.setAllowCredentials(true)
        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/**", configuration)
        return source
    }

    @JvmStatic
    fun filter(originPatternsCsv: String): CorsFilter {
        val filter = CorsFilter(source(originPatternsCsv))
        filter.setCorsProcessor(LenientProcessor())
        return filter
    }

    /**
     * A disallowed Origin on a non-preflight request gets no CORS headers (the browser enforces that) instead of the
     * default 403, so server-to-server callers that happen to send an Origin header keep their normal response.
     * Preflight requests from disallowed origins, methods or headers are still rejected.
     */
    internal class LenientProcessor : DefaultCorsProcessor() {
        @Throws(IOException::class)
        override fun handleInternal(
            request: ServerHttpRequest,
            response: ServerHttpResponse,
            config: CorsConfiguration,
            preFlightRequest: Boolean,
        ): Boolean {
            if (!preFlightRequest && config.checkOrigin(request.headers.origin) == null) {
                return true
            }
            return super.handleInternal(request, response, config, preFlightRequest)
        }
    }
}
