package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.boot.javaSplit
import com.pawbridge.backend.boot.javaTrim
import com.pawbridge.backend.boot.security.GatewayCors
import com.pawbridge.backend.user.internal.session.application.AuthSessions
import com.pawbridge.backend.user.internal.session.infrastructure.AuthSessionPolicy
import com.pawbridge.backend.user.internal.session.infrastructure.JdbcAuthSessionStore
import com.pawbridge.backend.user.internal.session.infrastructure.JdbcAuthSessions
import com.pawbridge.backend.user.internal.session.infrastructure.JwtSessionTokens
import com.pawbridge.backend.user.internal.session.infrastructure.RefreshTokens
import com.pawbridge.backend.user.internal.session.web.AuthSessionController
import com.pawbridge.backend.user.internal.session.web.AuthSessionExceptionHandler
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.DependsOn
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.util.ErrorHandler

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pawbridge.auth", name = ["sessions-enabled"], havingValue = "true", matchIfMissing = false)
class AuthSessionConfiguration {
    @Bean
    fun sessionTokens(
        writer: PostWriteSettings,
        @Value("\${jwt.access-token-expiration}") accessExpiration: Long,
    ): JwtSessionTokens {
        return JwtSessionTokens(writer.jwtKey(), accessExpiration)
    }

    @Bean
    fun authSessionPolicy(
        @Value("\${jwt.refresh-token-expiration}") idleLifetimeMillis: Long,
        @Value("\${pawbridge.auth.session-absolute-days:30}") absoluteLifetimeDays: Int,
    ): AuthSessionPolicy {
        return AuthSessionPolicy.of(idleLifetimeMillis, absoluteLifetimeDays)
    }

    @Bean
    fun refreshTokens(): RefreshTokens {
        return RefreshTokens()
    }

    @Bean
    fun authSessionStore(
        @Qualifier("writeJdbc") jdbc: JdbcTemplate,
        policy: AuthSessionPolicy,
        refreshTokens: RefreshTokens,
    ): JdbcAuthSessionStore {
        return JdbcAuthSessionStore(jdbc, policy, refreshTokens)
    }

    @Bean
    fun authSessions(
        @Qualifier("writeJdbc") jdbc: JdbcTemplate,
        @Qualifier("writeTransactionManager") manager: JdbcTransactionManager,
        tokens: JwtSessionTokens,
        store: JdbcAuthSessionStore,
        refreshTokens: RefreshTokens,
    ): AuthSessions {
        val transaction = TransactionTemplate(manager)
        transaction.timeout = 15
        return JdbcAuthSessions(jdbc, transaction, tokens, store, refreshTokens)
    }

    @Bean
    fun authSessionController(
        sessions: AuthSessions,
        @Value(GatewayCors.ORIGIN_PATTERNS_VALUE) corsOriginPatterns: String,
    ): AuthSessionController {
        return AuthSessionController(sessions, exactOrigins(corsOriginPatterns))
    }

    @Bean
    fun authSessionExceptionHandler(): AuthSessionExceptionHandler {
        return AuthSessionExceptionHandler()
    }

    /** Runs with the other retention jobs: only when pawbridge.jobs.cleanup-enabled=true. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(
        prefix = "pawbridge.jobs",
        name = ["cleanup-enabled"],
        havingValue = "true",
        matchIfMissing = false,
    )
    @EnableScheduling
    internal class AuthSessionCleanupConfiguration {
        @Bean
        @DependsOn("writeDataSource")
        fun authSessionCleanupScheduler(): ThreadPoolTaskScheduler {
            val scheduler = ThreadPoolTaskScheduler()
            scheduler.poolSize = 1
            scheduler.setThreadNamePrefix("auth-session-cleanup-")
            scheduler.setAcceptTasksAfterContextClose(false)
            scheduler.setWaitForTasksToCompleteOnShutdown(false)
            scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false)
            scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
            scheduler.setRemoveOnCancelPolicy(true)
            scheduler.setAwaitTerminationSeconds(5)
            scheduler.setErrorHandler(
                ErrorHandler { failure -> log.warn("Auth session cleanup failed ({})", failure.javaClass.simpleName) },
            )
            return scheduler
        }

        @Bean
        fun authSessionCleanupJob(
            store: JdbcAuthSessionStore,
            @Qualifier("writeTransactionManager") manager: JdbcTransactionManager,
        ): AuthSessionCleanupJob {
            val transaction = TransactionTemplate(manager)
            transaction.timeout = 15
            return AuthSessionCleanupJob(store, transaction)
        }

        private companion object {
            private val log = LoggerFactory.getLogger(AuthSessionCleanupConfiguration::class.java)
        }
    }

    class AuthSessionCleanupJob(
        private val store: JdbcAuthSessionStore,
        private val transaction: TransactionTemplate,
    ) {
        /** Small batches in separate transactions keep each delete short; leftovers wait for the next run. */
        @Scheduled(scheduler = "authSessionCleanupScheduler", initialDelay = 600000, fixedDelay = 3600000)
        fun deleteFinishedSessions() {
            for (batch in 0 until MAX_BATCHES_PER_RUN) {
                val deleted = transaction.execute { _ -> store.deleteFinished(BATCH_SIZE) }
                if (deleted == null || deleted < BATCH_SIZE) {
                    return
                }
            }
        }

        companion object {
            const val BATCH_SIZE: Int = 1000
            const val MAX_BATCHES_PER_RUN: Int = 50
        }
    }

    companion object {
        /** Wildcard CORS patterns are left out on purpose: cookie endpoints only trust explicitly listed origins. */
        @JvmStatic
        fun exactOrigins(corsOriginPatterns: String): List<String> {
            return javaSplit(corsOriginPatterns, ',')
                .map { javaTrim(it) }
                .filter { origin -> origin.isNotEmpty() }
                .filter { origin -> !origin.contains("*") }
        }
    }
}
