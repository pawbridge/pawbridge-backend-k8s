package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.boot.security.LegacyApiPathFilter
import com.pawbridge.backend.boot.security.LostSearchLimitsFilter
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Servlet filters for the features the API Gateway used to provide. CORS lives in [ReadSecurityConfiguration]
 * (the preflight must pass Spring Security) and health checks in the actuator properties.
 */
@Configuration(proxyBeanMethods = false)
class GatewayFeaturesConfiguration {
    /** Temporary: remove together with the old frontend paths (see [LegacyApiPathFilter]). */
    @Bean
    @ConditionalOnProperty(
        prefix = "pawbridge.legacy-api-paths",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    fun legacyApiPathFilter(): FilterRegistrationBean<LegacyApiPathFilter> {
        val registration = FilterRegistrationBean(LegacyApiPathFilter())
        registration.order = LEGACY_PATH_ORDER
        registration.addUrlPatterns("/api/*")
        return registration
    }

    @Bean
    @ConditionalOnProperty(prefix = "pawbridge.animal-lost-search", name = ["enabled"], havingValue = "true")
    fun lostSearchLimitsFilter(
        @Value("\${lost-search.max-request-bytes:6291456}") maxBytes: Int,
        @Value("\${lost-search.max-concurrent:2}") maxConcurrent: Int,
        @Value("\${lost-search.requests-per-minute:30}") requestsPerMinute: Int,
        @Value("\${lost-search.timeout-seconds:50}") timeoutSeconds: Int,
    ): FilterRegistrationBean<LostSearchLimitsFilter> {
        val registration = FilterRegistrationBean(
            LostSearchLimitsFilter(maxBytes, maxConcurrent, requestsPerMinute, timeoutSeconds),
        )
        registration.order = LOST_SEARCH_ORDER
        registration.addUrlPatterns(LostSearchLimitsFilter.PATH, LostSearchLimitsFilter.PATH + "/")
        return registration
    }

    companion object {
        /** Spring Security's filter chain runs at order -100; the path rewrite must see the request first. */
        const val LEGACY_PATH_ORDER: Int = -150

        /** After Security, so rejections already carry the CORS headers and the public route rule has been applied. */
        const val LOST_SEARCH_ORDER: Int = -50
    }
}
