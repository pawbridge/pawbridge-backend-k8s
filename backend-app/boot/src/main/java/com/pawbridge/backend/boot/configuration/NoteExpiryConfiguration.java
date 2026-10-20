package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.community.internal.note.application.NoteExpiry;
import com.pawbridge.backend.community.internal.note.infrastructure.JdbcNoteExpiry;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
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

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pawbridge.jobs", name = "cleanup-enabled", havingValue = "true", matchIfMissing = false)
@EnableScheduling
public class NoteExpiryConfiguration {
    private static final Logger log = LoggerFactory.getLogger(NoteExpiryConfiguration.class);

    @Bean
    NoteExpiry noteExpiry(@Qualifier("writeJdbc") JdbcTemplate jdbc,
            @Qualifier("writeTransactionManager") JdbcTransactionManager manager,
            @Qualifier("readClock") Clock clock) {
        var transaction = new TransactionTemplate(manager);
        transaction.setTimeout(15);
        return new JdbcNoteExpiry(jdbc, transaction, clock);
    }

    @Bean
    @DependsOn("writeDataSource")
    ThreadPoolTaskScheduler noteExpiryScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("private-note-expiry-");
        scheduler.setAcceptTasksAfterContextClose(false);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setAwaitTerminationSeconds(5);
        scheduler.setErrorHandler(failure -> log.warn("Private note expiry failed ({})", failure.getClass().getSimpleName()));
        return scheduler;
    }

    @Bean
    NoteExpiryJob noteExpiryJob(NoteExpiry expiry) {
        return new NoteExpiryJob(expiry);
    }

    public static final class NoteExpiryJob {
        private final NoteExpiry expiry;

        public NoteExpiryJob(NoteExpiry expiry) {
            this.expiry = expiry;
        }

        @Scheduled(scheduler = "noteExpiryScheduler", initialDelay = 300000, fixedDelay = 300000)
        public void expire() {
            expiry.expire();
        }
    }
}
