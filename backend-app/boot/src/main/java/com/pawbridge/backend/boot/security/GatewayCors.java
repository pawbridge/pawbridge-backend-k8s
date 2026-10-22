package com.pawbridge.backend.boot.security;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.DefaultCorsProcessor;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * CORS policy moved from the API Gateway {@code globalcors} block (api-gateway application.yml, {@code '[/**]'}).
 * Origins default to the original list and can be replaced with {@code pawbridge.cors.allowed-origin-patterns}.
 */
public final class GatewayCors {
    /** Comma separated copy of the Gateway allowedOriginPatterns. */
    public static final String DEFAULT_ORIGIN_PATTERNS = "http://localhost:3000,http://127.0.0.1:3000,"
            + "http://localhost:5173,http://127.0.0.1:5173,https://pawbridge.kr,https://www.pawbridge.kr,https://*.pawbridge.kr";

    private GatewayCors() {}

    public static CorsConfigurationSource source(String originPatternsCsv) {
        var configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(Arrays.stream(originPatternsCsv.split(",")).map(String::trim)
                .filter(value -> !value.isEmpty()).toList());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "x-user-id"));
        configuration.setExposedHeaders(List.of("Retry-After", "Authorization"));
        configuration.setAllowCredentials(true);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    public static CorsFilter filter(String originPatternsCsv) {
        var filter = new CorsFilter(source(originPatternsCsv));
        filter.setCorsProcessor(new LenientProcessor());
        return filter;
    }

    /**
     * A disallowed Origin on a non-preflight request gets no CORS headers (the browser enforces that) instead of the
     * default 403, so server-to-server callers that happen to send an Origin header keep their normal response.
     * Preflight requests from disallowed origins, methods or headers are still rejected.
     */
    static final class LenientProcessor extends DefaultCorsProcessor {
        @Override
        protected boolean handleInternal(ServerHttpRequest request, ServerHttpResponse response,
                CorsConfiguration config, boolean preFlightRequest) throws IOException {
            if (!preFlightRequest && config.checkOrigin(request.getHeaders().getOrigin()) == null) return true;
            return super.handleInternal(request, response, config, preFlightRequest);
        }
    }
}
