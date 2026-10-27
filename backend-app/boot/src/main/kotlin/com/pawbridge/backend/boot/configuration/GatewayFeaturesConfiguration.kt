package com.pawbridge.backend.boot.configuration

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
        /** After Security, so rejections already carry the CORS headers and the public route rule has been applied. */
        const val LOST_SEARCH_ORDER: Int = -50
    }
}
