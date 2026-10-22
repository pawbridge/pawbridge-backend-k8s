package com.pawbridge.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.pawbridge.backend.boot.configuration.GatewayFeaturesConfiguration;
import com.pawbridge.backend.boot.security.GatewayCors;
import com.pawbridge.backend.boot.security.LegacyApiPathFilter;
import com.pawbridge.backend.boot.security.LostSearchLimitsFilter;
import java.io.InputStream;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

class GatewayFeaturesConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(GatewayFeaturesConfiguration.class);

    @Test
    void given__defaults__when__starting__then__only_the_temporary_rewrite_is_registered_ahead_of_security() {
        runner.run(context -> {
            assertThat(context).hasBean("legacyApiPathFilter");
            var registration = (FilterRegistrationBean<?>) context.getBean("legacyApiPathFilter");
            assertThat(registration.getFilter()).isInstanceOf(LegacyApiPathFilter.class);
            assertThat(registration.getOrder()).isLessThan(-100);
            assertThat(context).doesNotHaveBean("lostSearchLimitsFilter");
        });
    }

    @Test
    void given__rewrite_disabled__when__starting__then__no_rewrite_filter() {
        runner.withPropertyValues("pawbridge.legacy-api-paths.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean("legacyApiPathFilter"));
    }

    @Test
    void given__lost_search_enabled__when__starting__then__limits_follow_security_with_gateway_defaults() {
        runner.withPropertyValues("pawbridge.animal-lost-search.enabled=true").run(context -> {
            var registration = (FilterRegistrationBean<?>) context.getBean("lostSearchLimitsFilter");
            assertThat(registration.getFilter()).isInstanceOf(LostSearchLimitsFilter.class);
            assertThat(registration.getOrder()).isGreaterThan(-100);
            assertThat(registration.getUrlPatterns()).contains(LostSearchLimitsFilter.PATH);
            var filter = registration.getFilter();
            assertThat(org.springframework.test.util.ReflectionTestUtils.getField(filter, "maxBytes")).isEqualTo(6_291_456);
            assertThat(org.springframework.test.util.ReflectionTestUtils.getField(filter, "requestsPerMinute")).isEqualTo(30);
            assertThat(((java.util.concurrent.Semaphore) org.springframework.test.util.ReflectionTestUtils.getField(filter, "active")).availablePermits()).isEqualTo(2);
        });
    }

    @Test
    void given__lost_search_properties__when__starting__then__override_the_defaults() {
        runner.withPropertyValues("pawbridge.animal-lost-search.enabled=true", "lost-search.max-request-bytes=100",
                "lost-search.max-concurrent=5", "lost-search.requests-per-minute=7", "lost-search.timeout-seconds=9").run(context -> {
            var filter = ((FilterRegistrationBean<?>) context.getBean("lostSearchLimitsFilter")).getFilter();
            assertThat(org.springframework.test.util.ReflectionTestUtils.getField(filter, "maxBytes")).isEqualTo(100);
            assertThat(org.springframework.test.util.ReflectionTestUtils.getField(filter, "requestsPerMinute")).isEqualTo(7);
            assertThat(((java.util.concurrent.Semaphore) org.springframework.test.util.ReflectionTestUtils.getField(filter, "active")).availablePermits()).isEqualTo(5);
        });
    }

    @Test
    void given__runtime_properties__when__reading__then__expose_only_health_and_info_and_keep_the_rewrite_on() throws Exception {
        var properties = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/application.properties")) { properties.load(in); }
        assertThat(properties.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health,info");
        assertThat(properties.getProperty("management.endpoint.health.probes.enabled")).isEqualTo("true");
        assertThat(properties.getProperty("management.endpoint.health.show-details")).isEqualTo("never");
        assertThat(properties.getProperty("management.health.mail.enabled")).isEqualTo("false");
        assertThat(properties.getProperty("pawbridge.legacy-api-paths.enabled")).isEqualTo("true");
        assertThat(properties.stringPropertyNames()).noneMatch(name -> name.contains("prometheus"));
    }

    @Test
    void given__default_origins__when__reading__then__match_the_original_gateway_list() {
        assertThat(GatewayCors.DEFAULT_ORIGIN_PATTERNS.split(",")).containsExactly("http://localhost:3000", "http://127.0.0.1:3000",
                "http://localhost:5173", "http://127.0.0.1:5173", "https://pawbridge.kr", "https://www.pawbridge.kr", "https://*.pawbridge.kr");
    }
}
