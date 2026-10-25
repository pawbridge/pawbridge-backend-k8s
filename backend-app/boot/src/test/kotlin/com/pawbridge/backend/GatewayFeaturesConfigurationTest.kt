package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.GatewayFeaturesConfiguration
import com.pawbridge.backend.boot.security.GatewayCors
import com.pawbridge.backend.boot.security.LegacyApiPathFilter
import com.pawbridge.backend.boot.security.LostSearchLimitsFilter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.test.util.ReflectionTestUtils
import java.util.Properties
import java.util.concurrent.Semaphore

class GatewayFeaturesConfigurationTest {
    private val runner = ApplicationContextRunner().withUserConfiguration(GatewayFeaturesConfiguration::class.java)

    @Test
    fun given__defaults__when__starting__then__only_the_temporary_rewrite_is_registered_ahead_of_security() {
        runner.run { context ->
            assertThat(context).hasBean("legacyApiPathFilter")
            val registration = context.getBean("legacyApiPathFilter") as FilterRegistrationBean<*>
            assertThat(registration.filter).isInstanceOf(LegacyApiPathFilter::class.java)
            assertThat(registration.order).isLessThan(-100)
            assertThat(context).doesNotHaveBean("lostSearchLimitsFilter")
        }
    }

    @Test
    fun given__rewrite_disabled__when__starting__then__no_rewrite_filter() {
        runner.withPropertyValues("pawbridge.legacy-api-paths.enabled=false")
            .run { context -> assertThat(context).doesNotHaveBean("legacyApiPathFilter") }
    }

    @Test
    fun given__lost_search_enabled__when__starting__then__limits_follow_security_with_gateway_defaults() {
        runner.withPropertyValues("pawbridge.animal-lost-search.enabled=true").run { context ->
            val registration = context.getBean("lostSearchLimitsFilter") as FilterRegistrationBean<*>
            assertThat(registration.filter).isInstanceOf(LostSearchLimitsFilter::class.java)
            assertThat(registration.order).isGreaterThan(-100)
            assertThat(registration.urlPatterns).contains(LostSearchLimitsFilter.PATH)
            val filter = registration.filter
            assertThat(ReflectionTestUtils.getField(filter, "maxBytes")).isEqualTo(6_291_456)
            assertThat(ReflectionTestUtils.getField(filter, "requestsPerMinute")).isEqualTo(30)
            assertThat((ReflectionTestUtils.getField(filter, "active") as Semaphore).availablePermits()).isEqualTo(2)
        }
    }

    @Test
    fun given__lost_search_properties__when__starting__then__override_the_defaults() {
        runner.withPropertyValues(
            "pawbridge.animal-lost-search.enabled=true",
            "lost-search.max-request-bytes=100",
            "lost-search.max-concurrent=5",
            "lost-search.requests-per-minute=7",
            "lost-search.timeout-seconds=9",
        ).run { context ->
            val filter = (context.getBean("lostSearchLimitsFilter") as FilterRegistrationBean<*>).filter
            assertThat(ReflectionTestUtils.getField(filter, "maxBytes")).isEqualTo(100)
            assertThat(ReflectionTestUtils.getField(filter, "requestsPerMinute")).isEqualTo(7)
            assertThat((ReflectionTestUtils.getField(filter, "active") as Semaphore).availablePermits()).isEqualTo(5)
        }
    }

    @Test
    fun given__runtime_properties__when__reading__then__expose_only_health_info_and_prometheus_on_a_separate_loopback_port_and_keep_the_rewrite_on() {
        val properties = Properties()
        javaClass.getResourceAsStream("/application.properties").use { input -> properties.load(input) }
        assertThat(properties.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health,info,prometheus")
        assertThat(properties.getProperty("management.server.port")).isEqualTo("\${PAWBRIDGE_MANAGEMENT_PORT:9464}")
        assertThat(properties.getProperty("management.server.address"))
            .isEqualTo("\${PAWBRIDGE_MANAGEMENT_ADDRESS:127.0.0.1}")
        assertThat(properties.getProperty("management.endpoint.health.probes.enabled")).isEqualTo("true")
        assertThat(properties.getProperty("management.endpoint.health.show-details")).isEqualTo("never")
        assertThat(properties.getProperty("management.health.mail.enabled")).isEqualTo("false")
        assertThat(properties.getProperty("pawbridge.legacy-api-paths.enabled")).isEqualTo("true")
        assertThat(properties.getProperty("server.address")).isEqualTo("127.0.0.1")
    }

    @Test
    fun given__default_origins__when__reading__then__match_the_original_gateway_list() {
        assertThat(GatewayCors.DEFAULT_ORIGIN_PATTERNS.split(",")).containsExactly(
            "http://localhost:3000",
            "http://127.0.0.1:3000",
            "http://localhost:5173",
            "http://127.0.0.1:5173",
            "https://pawbridge.kr",
            "https://www.pawbridge.kr",
            "https://*.pawbridge.kr",
        )
    }
}
