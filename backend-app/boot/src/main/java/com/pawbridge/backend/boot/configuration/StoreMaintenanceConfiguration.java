package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.store.internal.catalog.infrastructure.KoreanSearchTerms;
import com.pawbridge.backend.store.internal.directorder.infrastructure.JdbcProductSearchRebuild;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.support.TransactionTemplate;

/** Store search rebuild only; both the Store feature and the original rebuild flag must be explicit. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pawbridge", name = {"store-maintenance.enabled", "search.rebuild-enabled"},
        havingValue = "true", matchIfMissing = false)
@EnableScheduling
public class StoreMaintenanceConfiguration {
    @Bean
    NamedParameterJdbcTemplate storeSearchRebuildJdbc(@Qualifier("writeDataSource") DataSource source) {
        // Original PostgresqlSearchDocuments set a 5-second query timeout on its own writer JDBC.
        var jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(5);
        return new NamedParameterJdbcTemplate(jdbc);
    }

    @Bean
    TransactionTemplate storeSearchRebuildTransaction(@Qualifier("writeTransactionManager") JdbcTransactionManager manager) {
        // Original rebuildPage used @Transactional(timeout=10).
        var transaction = new TransactionTemplate(manager);
        transaction.setTimeout(10);
        return transaction;
    }

    @Bean(destroyMethod = "close")
    KoreanSearchTerms storeSearchRebuildTerms() { return new KoreanSearchTerms(); }

    @Bean
    JdbcProductSearchRebuild storeProductSearchRebuild(@Qualifier("storeSearchRebuildJdbc") NamedParameterJdbcTemplate jdbc,
            @Qualifier("storeSearchRebuildTransaction") TransactionTemplate transaction,
            @Qualifier("storeSearchRebuildTerms") KoreanSearchTerms terms) {
        return new JdbcProductSearchRebuild(jdbc, transaction, terms);
    }

    @Bean
    @DependsOn("writeDataSource")
    ThreadPoolTaskScheduler storeMaintenanceScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("store-maintenance-");
        scheduler.setAcceptTasksAfterContextClose(false);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setAwaitTerminationSeconds(15);
        scheduler.setErrorHandler(failure -> org.slf4j.LoggerFactory.getLogger(StoreMaintenanceConfiguration.class)
                .warn("Store maintenance failed ({})", failure.getClass().getSimpleName()));
        return scheduler;
    }

    @Bean
    SearchRebuildJob storeSearchRebuildJob(JdbcProductSearchRebuild rebuild) { return new SearchRebuildJob(rebuild); }

    public static final class SearchRebuildJob {
        private final JdbcProductSearchRebuild rebuild;
        public SearchRebuildJob(JdbcProductSearchRebuild rebuild) { this.rebuild = rebuild; }

        @Scheduled(scheduler = "storeMaintenanceScheduler", fixedDelayString = "${pawbridge.search.rebuild-delay-ms:5000}")
        public void rebuild() { rebuild.rebuildPage(100); }
    }
}
