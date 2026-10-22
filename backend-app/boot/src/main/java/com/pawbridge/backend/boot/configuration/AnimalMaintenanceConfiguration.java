package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.animal.internal.maintenance.infrastructure.JdbcAnimalOutboxRetention;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pawbridge", name = {"animal-maintenance.enabled", "jobs.cleanup-enabled"},
        havingValue = "true", matchIfMissing = false)
@EnableScheduling
public class AnimalMaintenanceConfiguration {
    @Bean
    JdbcAnimalOutboxRetention animalOutboxRetention(@Qualifier("writeJdbc") JdbcTemplate jdbc,
            @Qualifier("writeTransactionManager") PlatformTransactionManager manager) {
        // Original @Transactional uses the manager default; do not inherit a short HTTP-write timeout.
        return new JdbcAnimalOutboxRetention(jdbc, new TransactionTemplate(manager));
    }

    @Bean
    @DependsOn("writeDataSource")
    ThreadPoolTaskScheduler animalMaintenanceScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("animal-maintenance-");
        scheduler.setAcceptTasksAfterContextClose(false);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setAwaitTerminationSeconds(15);
        scheduler.setErrorHandler(failure -> org.slf4j.LoggerFactory.getLogger(AnimalMaintenanceConfiguration.class)
                .warn("Animal maintenance failed ({})", failure.getClass().getSimpleName()));
        return scheduler;
    }

    @Bean
    OutboxRetentionJobs animalOutboxRetentionJobs(JdbcAnimalOutboxRetention retention) {
        return new OutboxRetentionJobs(retention);
    }

    public static final class OutboxRetentionJobs {
        private final JdbcAnimalOutboxRetention retention;
        public OutboxRetentionJobs(JdbcAnimalOutboxRetention retention) { this.retention = retention; }

        @Scheduled(scheduler = "animalMaintenanceScheduler", cron = "0 0 3 * * ?")
        public void cleanupOldOutboxEvents() { retention.cleanupOldOutboxEvents(); }
    }
}
