package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.community.internal.maintenance.infrastructure.JdbcCommunityEventRetention;
import com.pawbridge.backend.community.internal.post.infrastructure.JdbcPostSearchRebuild;
import com.pawbridge.backend.community.internal.post.infrastructure.JdbcPostWriter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pawbridge.community-maintenance", name = "enabled", havingValue = "true", matchIfMissing = false)
@Import({CommunityMaintenanceConfiguration.RetentionConfiguration.class,
        CommunityMaintenanceConfiguration.SearchConfiguration.class})
public class CommunityMaintenanceConfiguration {
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "pawbridge.jobs", name = "cleanup-enabled", havingValue = "true", matchIfMissing = false)
    @Import(CommunityMaintenanceSchedulingConfiguration.class)
    static class RetentionConfiguration {
        @Bean
        TransactionTemplate communityEventRetentionTransaction(
                @Qualifier("writeTransactionManager") JdbcTransactionManager manager) {
            return new TransactionTemplate(manager);
        }

        @Bean
        JdbcCommunityEventRetention communityEventRetention(@Qualifier("writeJdbc") JdbcTemplate jdbc,
                @Qualifier("communityEventRetentionTransaction") TransactionTemplate transaction) {
            return new JdbcCommunityEventRetention(jdbc, transaction);
        }

        @Bean
        EventRetentionJobs communityEventRetentionJobs(JdbcCommunityEventRetention retention) {
            return new EventRetentionJobs(retention);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "pawbridge.search", name = "rebuild-enabled", havingValue = "true", matchIfMissing = false)
    @Import(CommunityMaintenanceSchedulingConfiguration.class)
    static class SearchConfiguration {
        @Bean
        JdbcPostSearchRebuild communityPostSearchRebuild(@Qualifier("writeJdbc") JdbcTemplate jdbc,
                @Qualifier("writeTransaction") TransactionTemplate transaction, JdbcPostWriter writer) {
            return new JdbcPostSearchRebuild(jdbc, transaction, writer);
        }

        @Bean
        SearchRebuildJob communitySearchRebuildJob(JdbcPostSearchRebuild rebuild) {
            return new SearchRebuildJob(rebuild);
        }
    }

    public static final class EventRetentionJobs {
        private final JdbcCommunityEventRetention retention;
        public EventRetentionJobs(JdbcCommunityEventRetention retention) { this.retention = retention; }

        @Scheduled(scheduler = "communityMaintenanceScheduler", cron = "0 0 3 * * ?")
        public void cleanupOldOutboxEvents() { retention.cleanupOldOutboxEvents(); }

        @Scheduled(scheduler = "communityMaintenanceScheduler", cron = "0 0 4 * * ?")
        public void cleanupOldProcessedEvents() { retention.cleanupOldProcessedEvents(); }
    }

    public static final class SearchRebuildJob {
        private final JdbcPostSearchRebuild rebuild;
        public SearchRebuildJob(JdbcPostSearchRebuild rebuild) { this.rebuild = rebuild; }

        @Scheduled(scheduler = "communityMaintenanceScheduler", fixedDelayString = "${pawbridge.search.rebuild-delay-ms:5000}")
        public void rebuild() { rebuild.rebuildPage(100); }
    }
}

@Configuration(proxyBeanMethods = false)
@EnableScheduling
class CommunityMaintenanceSchedulingConfiguration {
    @Bean
    @DependsOn({"writeDataSource", "postWriter"})
    ThreadPoolTaskScheduler communityMaintenanceScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("community-maintenance-");
        scheduler.setAcceptTasksAfterContextClose(false);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setAwaitTerminationSeconds(15);
        scheduler.setErrorHandler(failure -> org.slf4j.LoggerFactory.getLogger(CommunityMaintenanceConfiguration.class)
                .warn("Community maintenance failed ({})", failure.getClass().getSimpleName()));
        return scheduler;
    }
}
