package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.user.api.UserEventRetention;
import com.pawbridge.backend.user.internal.maintenance.infrastructure.JdbcUserEventRetention;
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
@ConditionalOnProperty(prefix = "pawbridge", name = {"user-maintenance.enabled", "jobs.cleanup-enabled"},
        havingValue = "true", matchIfMissing = false)
@EnableScheduling
public class UserMaintenanceConfiguration {
    @Bean
    UserEventRetention userEventRetention(@Qualifier("writeJdbc") JdbcTemplate jdbc,
            @Qualifier("writeTransactionManager") PlatformTransactionManager manager) {
        // Original @Transactional uses the manager default; do not inherit a short HTTP-write timeout.
        return new JdbcUserEventRetention(jdbc, new TransactionTemplate(manager));
    }

    @Bean
    @DependsOn("writeDataSource")
    ThreadPoolTaskScheduler userMaintenanceScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("user-maintenance-");
        scheduler.setAcceptTasksAfterContextClose(false);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setAwaitTerminationSeconds(15);
        scheduler.setErrorHandler(failure -> org.slf4j.LoggerFactory.getLogger(UserMaintenanceConfiguration.class)
                .warn("User maintenance failed ({})", failure.getClass().getSimpleName()));
        return scheduler;
    }

    @Bean
    EventRetentionJobs userEventRetentionJobs(UserEventRetention retention) {
        return new EventRetentionJobs(retention);
    }

    public static final class EventRetentionJobs {
        private final UserEventRetention retention;
        public EventRetentionJobs(UserEventRetention retention) { this.retention = retention; }

        @Scheduled(scheduler = "userMaintenanceScheduler", cron = "0 0 3 * * *")
        public void cleanupOldOutboxEvents() { retention.cleanupOldOutboxEvents(); }

        @Scheduled(scheduler = "userMaintenanceScheduler", cron = "0 30 3 * * *")
        public void cleanupOldProcessedEvents() { retention.cleanupOldProcessedEvents(); }
    }
}
