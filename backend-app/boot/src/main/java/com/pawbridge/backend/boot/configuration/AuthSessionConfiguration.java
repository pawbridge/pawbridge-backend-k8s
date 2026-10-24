package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.boot.security.GatewayCors;
import com.pawbridge.backend.user.internal.session.application.AuthSessions;
import com.pawbridge.backend.user.internal.session.infrastructure.AuthSessionPolicy;
import com.pawbridge.backend.user.internal.session.infrastructure.JdbcAuthSessionStore;
import com.pawbridge.backend.user.internal.session.infrastructure.JdbcAuthSessions;
import com.pawbridge.backend.user.internal.session.infrastructure.JwtSessionTokens;
import com.pawbridge.backend.user.internal.session.infrastructure.RefreshTokens;
import com.pawbridge.backend.user.internal.session.web.AuthSessionController;
import com.pawbridge.backend.user.internal.session.web.AuthSessionExceptionHandler;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="pawbridge.auth",name="sessions-enabled",havingValue="true",matchIfMissing=false)
public class AuthSessionConfiguration {
    @Bean
    JwtSessionTokens sessionTokens(PostWriteSettings writer,
            @Value("${jwt.access-token-expiration}") long accessExpiration) {
        return new JwtSessionTokens(writer.jwtKey(), accessExpiration);
    }

    @Bean
    AuthSessionPolicy authSessionPolicy(
            @Value("${jwt.refresh-token-expiration}") long idleLifetimeMillis,
            @Value("${pawbridge.auth.session-absolute-days:30}") int absoluteLifetimeDays) {
        return AuthSessionPolicy.of(idleLifetimeMillis, absoluteLifetimeDays);
    }

    @Bean
    RefreshTokens refreshTokens() {
        return new RefreshTokens();
    }

    @Bean
    JdbcAuthSessionStore authSessionStore(@Qualifier("writeJdbc") JdbcTemplate jdbc, AuthSessionPolicy policy,
            RefreshTokens refreshTokens) {
        return new JdbcAuthSessionStore(jdbc, policy, refreshTokens);
    }

    @Bean
    AuthSessions authSessions(@Qualifier("writeJdbc") JdbcTemplate jdbc,
            @Qualifier("writeTransactionManager") JdbcTransactionManager manager, JwtSessionTokens tokens,
            JdbcAuthSessionStore store, RefreshTokens refreshTokens) {
        var transaction = new TransactionTemplate(manager);
        transaction.setTimeout(15);
        return new JdbcAuthSessions(jdbc, transaction, tokens, store, refreshTokens);
    }

    @Bean
    AuthSessionController authSessionController(AuthSessions sessions,
            @Value(GatewayCors.ORIGIN_PATTERNS_VALUE) String corsOriginPatterns) {
        return new AuthSessionController(sessions, exactOrigins(corsOriginPatterns));
    }

    @Bean
    AuthSessionExceptionHandler authSessionExceptionHandler() {
        return new AuthSessionExceptionHandler();
    }

    /** Wildcard CORS patterns are left out on purpose: cookie endpoints only trust explicitly listed origins. */
    static List<String> exactOrigins(String corsOriginPatterns) {
        return Arrays.stream(corsOriginPatterns.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .filter(origin -> !origin.contains("*"))
                .toList();
    }

    /** Runs with the other retention jobs: only when pawbridge.jobs.cleanup-enabled=true. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "pawbridge.jobs", name = "cleanup-enabled", havingValue = "true", matchIfMissing = false)
    @EnableScheduling
    static class AuthSessionCleanupConfiguration {
        private static final Logger log = LoggerFactory.getLogger(AuthSessionCleanupConfiguration.class);

        @Bean
        @DependsOn("writeDataSource")
        ThreadPoolTaskScheduler authSessionCleanupScheduler() {
            var scheduler = new ThreadPoolTaskScheduler();
            scheduler.setPoolSize(1);
            scheduler.setThreadNamePrefix("auth-session-cleanup-");
            scheduler.setAcceptTasksAfterContextClose(false);
            scheduler.setWaitForTasksToCompleteOnShutdown(false);
            scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
            scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
            scheduler.setRemoveOnCancelPolicy(true);
            scheduler.setAwaitTerminationSeconds(5);
            scheduler.setErrorHandler(failure ->
                    log.warn("Auth session cleanup failed ({})", failure.getClass().getSimpleName()));
            return scheduler;
        }

        @Bean
        AuthSessionCleanupJob authSessionCleanupJob(JdbcAuthSessionStore store,
                @Qualifier("writeTransactionManager") JdbcTransactionManager manager) {
            var transaction = new TransactionTemplate(manager);
            transaction.setTimeout(15);
            return new AuthSessionCleanupJob(store, transaction);
        }
    }

    public static final class AuthSessionCleanupJob {
        static final int BATCH_SIZE = 1000;
        static final int MAX_BATCHES_PER_RUN = 50;

        private final JdbcAuthSessionStore store;
        private final TransactionTemplate transaction;

        public AuthSessionCleanupJob(JdbcAuthSessionStore store, TransactionTemplate transaction) {
            this.store = store;
            this.transaction = transaction;
        }

        /** Small batches in separate transactions keep each delete short; leftovers wait for the next run. */
        @Scheduled(scheduler = "authSessionCleanupScheduler", initialDelay = 600000, fixedDelay = 3600000)
        public void deleteFinishedSessions() {
            for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
                Integer deleted = transaction.execute(status -> store.deleteFinished(BATCH_SIZE));
                if (deleted == null || deleted < BATCH_SIZE) {
                    return;
                }
            }
        }
    }
}
