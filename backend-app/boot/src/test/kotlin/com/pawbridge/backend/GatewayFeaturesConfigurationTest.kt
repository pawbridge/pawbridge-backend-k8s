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
    fun `기본 설정일 때__기동하면__임시 재작성 필터만 보안보다 앞에 등록된다`() {
        // given
        // 클래스의 runner는 설정 클래스만 올린 기본 상태다

        // when
        runner.run { context ->
            // then
            assertThat(context).hasBean("legacyApiPathFilter")
            val registration = context.getBean("legacyApiPathFilter") as FilterRegistrationBean<*>
            assertThat(registration.filter).isInstanceOf(LegacyApiPathFilter::class.java)
            assertThat(registration.order).isLessThan(-100)
            assertThat(context).doesNotHaveBean("lostSearchLimitsFilter")
        }
    }

    @Test
    fun `재작성을 껐을 때__기동하면__재작성 필터가 없다`() {
        // given
        val disabled = runner.withPropertyValues("pawbridge.legacy-api-paths.enabled=false")

        // when
        disabled.run { context ->
            // then
            assertThat(context).doesNotHaveBean("legacyApiPathFilter")
        }
    }

    @Test
    fun `분실 검색을 켰을 때__기동하면__한도 필터가 보안 뒤에서 게이트웨이 기본값으로 동작한다`() {
        // given
        val enabled = runner.withPropertyValues("pawbridge.animal-lost-search.enabled=true")

        // when
        enabled.run { context ->
            // then
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
    fun `분실 검색 속성을 줬을 때__기동하면__기본값을 덮어쓴다`() {
        // given
        val overridden = runner.withPropertyValues(
            "pawbridge.animal-lost-search.enabled=true",
            "lost-search.max-request-bytes=100",
            "lost-search.max-concurrent=5",
            "lost-search.requests-per-minute=7",
            "lost-search.timeout-seconds=9",
        )

        // when
        overridden.run { context ->
            // then
            val filter = (context.getBean("lostSearchLimitsFilter") as FilterRegistrationBean<*>).filter
            assertThat(ReflectionTestUtils.getField(filter, "maxBytes")).isEqualTo(100)
            assertThat(ReflectionTestUtils.getField(filter, "requestsPerMinute")).isEqualTo(7)
            assertThat((ReflectionTestUtils.getField(filter, "active") as Semaphore).availablePermits()).isEqualTo(5)
        }
    }

    @Test
    fun `런타임 속성일 때__읽으면__health info prometheus만 루프백 별도 포트로 노출하고 재작성을 켜 둔다`() {
        // given
        val properties = Properties()

        // when
        javaClass.getResourceAsStream("/application.properties").use { input -> properties.load(input) }

        // then
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
    fun `기본 출처일 때__읽으면__원래 게이트웨이 목록과 같다`() {
        // given
        val origins = GatewayCors.DEFAULT_ORIGIN_PATTERNS

        // when
        val split = origins.split(",")

        // then
        assertThat(split).containsExactly(
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
