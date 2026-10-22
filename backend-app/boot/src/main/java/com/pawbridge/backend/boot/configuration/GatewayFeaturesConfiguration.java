package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.boot.security.LegacyApiPathFilter;
import com.pawbridge.backend.boot.security.LostSearchLimitsFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Servlet filters for the features the API Gateway used to provide. CORS lives in {@link ReadSecurityConfiguration}
 * (the preflight must pass Spring Security) and health checks in the actuator properties.
 */
@Configuration(proxyBeanMethods = false)
public class GatewayFeaturesConfiguration {
    /** Spring Security's filter chain runs at order -100; the path rewrite must see the request first. */
    static final int LEGACY_PATH_ORDER = -150;
    /** After Security, so rejections already carry the CORS headers and the public route rule has been applied. */
    static final int LOST_SEARCH_ORDER = -50;

    /** Temporary: remove together with the old frontend paths (see {@link LegacyApiPathFilter}). */
    @Bean
    @ConditionalOnProperty(prefix = "pawbridge.legacy-api-paths", name = "enabled", havingValue = "true", matchIfMissing = true)
    FilterRegistrationBean<LegacyApiPathFilter> legacyApiPathFilter() {
        var registration = new FilterRegistrationBean<>(new LegacyApiPathFilter());
        registration.setOrder(LEGACY_PATH_ORDER);
        registration.addUrlPatterns("/api/*");
        return registration;
    }

    @Bean
    @ConditionalOnProperty(prefix = "pawbridge.animal-lost-search", name = "enabled", havingValue = "true")
    FilterRegistrationBean<LostSearchLimitsFilter> lostSearchLimitsFilter(
            @Value("${lost-search.max-request-bytes:6291456}") int maxBytes,
            @Value("${lost-search.max-concurrent:2}") int maxConcurrent,
            @Value("${lost-search.requests-per-minute:30}") int requestsPerMinute,
            @Value("${lost-search.timeout-seconds:50}") int timeoutSeconds) {
        var registration = new FilterRegistrationBean<>(
                new LostSearchLimitsFilter(maxBytes, maxConcurrent, requestsPerMinute, timeoutSeconds));
        registration.setOrder(LOST_SEARCH_ORDER);
        registration.addUrlPatterns(LostSearchLimitsFilter.PATH, LostSearchLimitsFilter.PATH + "/");
        return registration;
    }
}
