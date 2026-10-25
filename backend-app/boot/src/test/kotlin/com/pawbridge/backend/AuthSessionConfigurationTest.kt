package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.AuthSessionConfiguration
import com.pawbridge.backend.boot.configuration.PostWriteSettings
import com.pawbridge.backend.support.anyNonNull
import com.pawbridge.backend.user.internal.session.application.AuthSessions
import com.pawbridge.backend.user.internal.session.infrastructure.AuthSessionPolicy
import com.pawbridge.backend.user.internal.session.infrastructure.JdbcAuthSessionStore
import com.pawbridge.backend.user.internal.session.infrastructure.JwtSessionTokens
import com.pawbridge.backend.user.internal.session.web.AuthSessionController
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.`when` as whenever
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import javax.sql.DataSource

class AuthSessionConfigurationTest {
    @ParameterizedTest
    @ValueSource(strings = ["absent", "false"])
    fun given__flag_off_without_any_ttl__when__assembling__then__no_session_beans_or_key_inputs_needed(flag: String) {
        var runner = ApplicationContextRunner().withUserConfiguration(AuthSessionConfiguration::class.java)
        if (flag != "absent") {
            runner = runner.withPropertyValues("pawbridge.auth.sessions-enabled=false")
        }
        runner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBeansOfType(AuthSessions::class.java)).isEmpty()
            assertThat(context.getBeansOfType(JwtSessionTokens::class.java)).isEmpty()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["jwt.access-token-expiration", "jwt.refresh-token-expiration"])
    fun given__enabled_without_one_original_ttl__when__assembling__then__fail_start_without_inventing_a_default(
        missing: String,
    ) {
        var runner = inputs().withPropertyValues("pawbridge.auth.sessions-enabled=true")
        for (property in listOf("jwt.access-token-expiration", "jwt.refresh-token-expiration")) {
            if (property != missing) {
                runner = runner.withPropertyValues("$property=600000")
            }
        }
        runner.run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).hasStackTraceContaining(missing)
        }
    }

    @Test
    fun given__enabled_with_original_ttls__when__assembling__then__reuse_exact_existing_key_writer_and_manager() {
        val settings = F3TestInputs.settings()
        inputs(settings).withPropertyValues(
            "pawbridge.auth.sessions-enabled=true",
            "jwt.access-token-expiration=600000",
            "jwt.refresh-token-expiration=1234",
        ).run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBeansOfType(AuthSessions::class.java)).hasSize(1)
            assertThat(context.getBeansOfType(JwtSessionTokens::class.java)).hasSize(1)
            val tokens = context.getBean(JwtSessionTokens::class.java)
            assertThat(ReflectionTestUtils.getField(tokens, "key")).isSameAs(settings.jwtKey())
            assertThat(ReflectionTestUtils.getField(tokens, "accessExpiration")).isEqualTo(600000L)
            val sessions = context.getBean(AuthSessions::class.java)
            assertThat(ReflectionTestUtils.getField(sessions, "jdbc")).isSameAs(context.getBean("writeJdbc"))
            val tx = ReflectionTestUtils.getField(sessions, "transactions") as TransactionTemplate
            assertThat(tx.transactionManager).isSameAs(context.getBean("writeTransactionManager"))
            assertThat(tx.timeout).isEqualTo(15)
            assertThat(tx.isReadOnly).isFalse()
            assertThat(context.getBeansOfType(DataSource::class.java)).isEmpty()
            verifyNoInteractions(context.getBean("writeJdbc"), context.getBean("writeTransactionManager"))
        }
    }

    @Test
    fun given__enabled_with_defaults__when__assembling__then__use_the_refresh_ttl_as_idle_lifetime_and_thirty_absolute_days() {
        inputs().withPropertyValues(
            "pawbridge.auth.sessions-enabled=true",
            "jwt.access-token-expiration=600000",
            "jwt.refresh-token-expiration=1209600000",
        ).run { context ->
            assertThat(context).hasNotFailed()
            val policy = context.getBean(AuthSessionPolicy::class.java)
            assertThat(policy.idleLifetime).isEqualTo(Duration.ofDays(14))
            assertThat(policy.absoluteLifetime).isEqualTo(Duration.ofDays(30))
            assertThat(policy.maxActiveSessions).isEqualTo(5)
            assertThat(policy.reuseGrace).isEqualTo(Duration.ofSeconds(60))
            val store = context.getBean(JdbcAuthSessionStore::class.java)
            assertThat(ReflectionTestUtils.getField(store, "jdbc")).isSameAs(context.getBean("writeJdbc"))
            assertThat(context.getBeansOfType(AuthSessionConfiguration.AuthSessionCleanupJob::class.java)).isEmpty()
        }
    }

    @Test
    fun given__a_configured_absolute_lifetime__when__assembling__then__apply_it() {
        inputs().withPropertyValues(
            "pawbridge.auth.sessions-enabled=true",
            "jwt.access-token-expiration=600000",
            "jwt.refresh-token-expiration=1209600000",
            "pawbridge.auth.session-absolute-days=7",
        ).run { context ->
            assertThat(context.getBean(AuthSessionPolicy::class.java).absoluteLifetime).isEqualTo(Duration.ofDays(7))
        }
    }

    @Test
    fun given__cors_patterns_with_wildcards__when__assembling_the_controller__then__trust_only_exact_origins() {
        inputs().withPropertyValues(
            "pawbridge.auth.sessions-enabled=true",
            "jwt.access-token-expiration=600000",
            "jwt.refresh-token-expiration=1209600000",
            "pawbridge.cors.allowed-origin-patterns= https://www.pawbridge.kr ,https://*.pawbridge.kr,,https://pawbridge.kr",
        ).run { context ->
            val controller = context.getBean(AuthSessionController::class.java)
            @Suppress("UNCHECKED_CAST")
            val allowedOrigins = ReflectionTestUtils.getField(controller, "allowedOrigins") as Set<Any>
            assertThat(allowedOrigins).containsExactlyInAnyOrder("https://www.pawbridge.kr", "https://pawbridge.kr")
        }
    }

    @Test
    fun given__cleanup_jobs_enabled__when__assembling__then__register_the_session_cleanup_job_on_its_own_scheduler() {
        inputs().withPropertyValues(
            "pawbridge.auth.sessions-enabled=true",
            "jwt.access-token-expiration=600000",
            "jwt.refresh-token-expiration=1209600000",
            "pawbridge.jobs.cleanup-enabled=true",
        )
            .withInitializer { context ->
                context.beanFactory.registerSingleton("writeDataSource", mock(DataSource::class.java))
            }
            .run { context ->
                assertThat(context).hasNotFailed()
                val job = context.getBean(AuthSessionConfiguration.AuthSessionCleanupJob::class.java)
                assertThat(ReflectionTestUtils.getField(job, "store")).isSameAs(context.getBean(JdbcAuthSessionStore::class.java))
                val scheduled = AuthSessionConfiguration.AuthSessionCleanupJob::class.java
                    .getMethod("deleteFinishedSessions")
                    .getAnnotation(Scheduled::class.java)
                assertThat(scheduled.scheduler).isEqualTo("authSessionCleanupScheduler")
                assertThat(context.getBean("authSessionCleanupScheduler")).isNotNull()
            }
    }

    @Test
    fun given__a_full_batch_then_a_partial_one__when__cleaning__then__stop_after_the_partial_batch() {
        val store = mock(JdbcAuthSessionStore::class.java)
        whenever(store.deleteFinished(1000)).thenReturn(1000, 3)
        val manager = mock(PlatformTransactionManager::class.java)
        whenever(manager.getTransaction(any<TransactionDefinition>())).thenReturn(SimpleTransactionStatus())

        AuthSessionConfiguration.AuthSessionCleanupJob(store, TransactionTemplate(manager)).deleteFinishedSessions()

        verify(store, times(2)).deleteFinished(1000)
        verify(manager, times(2)).commit(anyNonNull<org.springframework.transaction.TransactionStatus>())
    }

    private fun inputs(): ApplicationContextRunner = inputs(F3TestInputs.settings())

    private fun inputs(settings: PostWriteSettings): ApplicationContextRunner {
        return ApplicationContextRunner().withUserConfiguration(AuthSessionConfiguration::class.java)
            .withInitializer { context ->
                context.beanFactory.registerSingleton("postWriteSettings", settings)
                context.beanFactory.registerSingleton("writeJdbc", mock(JdbcTemplate::class.java))
                context.beanFactory.registerSingleton("writeTransactionManager", mock(JdbcTransactionManager::class.java))
            }
    }
}
