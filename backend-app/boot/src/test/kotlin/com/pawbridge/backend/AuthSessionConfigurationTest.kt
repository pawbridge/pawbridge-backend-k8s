package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.AuthSessionConfiguration
import com.pawbridge.backend.boot.configuration.PostWriteSettings
import com.pawbridge.backend.user.internal.session.application.AuthSessions
import com.pawbridge.backend.user.internal.session.infrastructure.AuthSessionPolicy
import com.pawbridge.backend.user.internal.session.infrastructure.JdbcAuthSessionStore
import com.pawbridge.backend.user.internal.session.infrastructure.JwtSessionTokens
import com.pawbridge.backend.user.internal.session.web.AuthSessionController
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import io.jsonwebtoken.Jwts
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import javax.sql.DataSource

class AuthSessionConfigurationTest {
    @ParameterizedTest
    @ValueSource(strings = ["absent", "false"])
    fun `플래그가 꺼졌고 TTL이 없을 때__조립하면__세션 빈도 키 입력도 필요 없다`(flag: String) {
        // given
        var runner = ApplicationContextRunner().withUserConfiguration(AuthSessionConfiguration::class.java)
        if (flag != "absent") {
            runner = runner.withPropertyValues("pawbridge.auth.sessions-enabled=false")
        }

        // when
        runner.run { context ->
            // then
            assertThat(context).hasNotFailed()
            assertThat(context.getBeansOfType(AuthSessions::class.java)).isEmpty()
            assertThat(context.getBeansOfType(JwtSessionTokens::class.java)).isEmpty()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["jwt.access-token-expiration", "jwt.refresh-token-expiration"])
    fun `켜졌지만 원래 TTL 하나가 없을 때__조립하면__기본값을 만들지 않고 시작에 실패한다`(
        missing: String,
    ) {
        // given
        var runner = inputs().withPropertyValues("pawbridge.auth.sessions-enabled=true")
        for (property in listOf("jwt.access-token-expiration", "jwt.refresh-token-expiration")) {
            if (property != missing) {
                runner = runner.withPropertyValues("$property=600000")
            }
        }

        // when
        runner.run { context ->
            // then
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).hasStackTraceContaining(missing)
        }
    }

    @Test
    fun `켜지고 원래 TTL이 있을 때__조립하면__기존 키로 서명한 설정 만료의 접근 토큰을 발급한다`() {
        // given
        val settings = F3TestInputs.settings()
        val runner = inputs(settings).withPropertyValues(
            "pawbridge.auth.sessions-enabled=true",
            "jwt.access-token-expiration=600000",
            "jwt.refresh-token-expiration=1234",
        )

        // when
        runner.run { context ->
            // then
            assertThat(context).hasNotFailed()
            assertThat(context.getBeansOfType(AuthSessions::class.java)).hasSize(1)
            assertThat(context.getBeansOfType(JwtSessionTokens::class.java)).hasSize(1)
            val token = context.getBean(JwtSessionTokens::class.java)
                .access(JwtSessionTokens.UserClaims(1L, "user@example.invalid", "user", "ROLE_USER", null))
            val claims = Jwts.parser().verifyWith(settings.jwtKey()).build().parseSignedClaims(token).payload
            assertThat(claims.expiration.time - claims.issuedAt.time).isEqualTo(600_000L)
            assertThat(context.getBean(AuthSessionPolicy::class.java).idleLifetime).isEqualTo(Duration.ofMillis(1234))
            assertThat(context.getBeansOfType(DataSource::class.java)).isEmpty()
            confirmVerified(context.getBean("writeJdbc"), context.getBean("writeTransactionManager"))
        }
    }

    @Test
    fun `켜지고 기본값일 때__조립하면__리프레시 TTL을 유휴 수명으로 쓰고 절대 수명은 30일이다`() {
        // given
        val runner = inputs().withPropertyValues(
            "pawbridge.auth.sessions-enabled=true",
            "jwt.access-token-expiration=600000",
            "jwt.refresh-token-expiration=1209600000",
        )

        // when
        runner.run { context ->
            // then
            assertThat(context).hasNotFailed()
            val policy = context.getBean(AuthSessionPolicy::class.java)
            assertThat(policy.idleLifetime).isEqualTo(Duration.ofDays(14))
            assertThat(policy.absoluteLifetime).isEqualTo(Duration.ofDays(30))
            assertThat(policy.maxActiveSessions).isEqualTo(5)
            assertThat(policy.reuseGrace).isEqualTo(Duration.ofSeconds(60))
            assertThat(context.getBeansOfType(AuthSessionConfiguration.AuthSessionCleanupJob::class.java)).isEmpty()
        }
    }

    @Test
    fun `절대 수명이 설정됐을 때__조립하면__그 값을 적용한다`() {
        // given
        val runner = inputs().withPropertyValues(
            "pawbridge.auth.sessions-enabled=true",
            "jwt.access-token-expiration=600000",
            "jwt.refresh-token-expiration=1209600000",
            "pawbridge.auth.session-absolute-days=7",
        )

        // when
        runner.run { context ->
            // then
            assertThat(context.getBean(AuthSessionPolicy::class.java).absoluteLifetime).isEqualTo(Duration.ofDays(7))
        }
    }

    @Test
    fun `와일드카드 CORS 패턴이 있을 때__컨트롤러를 조립하면__정확한 출처만 신뢰한다`() {
        // given
        val runner = inputs().withPropertyValues(
            "pawbridge.auth.sessions-enabled=true",
            "jwt.access-token-expiration=600000",
            "jwt.refresh-token-expiration=1209600000",
            "pawbridge.cors.allowed-origin-patterns= https://www.pawbridge.kr ,https://*.pawbridge.kr,,https://pawbridge.kr",
        )

        // when
        runner.run { context ->
            // then
            val controller = context.getBean(AuthSessionController::class.java)
            assertThat(logoutStatus(controller, "https://www.pawbridge.kr")).isEqualTo(200)
            assertThat(logoutStatus(controller, "https://pawbridge.kr")).isEqualTo(200)
            assertThat(logoutStatus(controller, "https://app.pawbridge.kr")).isEqualTo(403)
            assertThat(logoutStatus(controller, "https://*.pawbridge.kr")).isEqualTo(403)
        }
    }

    @Test
    fun `정리 작업이 켜졌을 때__조립하면__세션 정리 작업을 전용 스케줄러에 등록한다`() {
        // given
        val writeDataSource = mockk<DataSource>()
        val runner = inputs().withPropertyValues(
            "pawbridge.auth.sessions-enabled=true",
            "jwt.access-token-expiration=600000",
            "jwt.refresh-token-expiration=1209600000",
            "pawbridge.jobs.cleanup-enabled=true",
        ).withInitializer { context ->
            context.beanFactory.registerSingleton("writeDataSource", writeDataSource)
        }

        // when
        runner.run { context ->
            // then
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(AuthSessionConfiguration.AuthSessionCleanupJob::class.java)
            val scheduled = AuthSessionConfiguration.AuthSessionCleanupJob::class.java
                .getMethod("deleteFinishedSessions")
                .getAnnotation(Scheduled::class.java)
            assertThat(scheduled.scheduler).isEqualTo("authSessionCleanupScheduler")
            assertThat(context.getBean("authSessionCleanupScheduler")).isNotNull()
        }
    }

    @Test
    fun `가득 찬 배치 뒤에 부분 배치가 올 때__정리하면__부분 배치 뒤에 멈춘다`() {
        // given
        val store = mockk<JdbcAuthSessionStore>()
        val manager = mockk<PlatformTransactionManager>()

        every { store.deleteFinished(1000) } returnsMany listOf(1000, 3)
        every { manager.getTransaction(any()) } returns SimpleTransactionStatus()
        justRun { manager.commit(any()) }

        // when
        AuthSessionConfiguration.AuthSessionCleanupJob(store, TransactionTemplate(manager)).deleteFinishedSessions()

        // then
        verify(exactly = 2) { store.deleteFinished(1000) }
        verify(exactly = 2) { manager.commit(any()) }
    }

    private fun logoutStatus(controller: AuthSessionController, origin: String): Int {
        val request = MockHttpServletRequest("POST", "/api/v1/auth/logout")
        request.addHeader("Origin", origin)
        request.contentType = "application/json"
        return controller.logout(request).statusCode.value()
    }

    private fun inputs(): ApplicationContextRunner = inputs(F3TestInputs.settings())

    private fun inputs(settings: PostWriteSettings): ApplicationContextRunner {
        return ApplicationContextRunner().withUserConfiguration(AuthSessionConfiguration::class.java)
            .withInitializer { context ->
                context.beanFactory.registerSingleton("postWriteSettings", settings)
                context.beanFactory.registerSingleton("writeJdbc", mockk<JdbcTemplate>())
                context.beanFactory.registerSingleton("writeTransactionManager", mockk<JdbcTransactionManager>())
            }
    }
}
