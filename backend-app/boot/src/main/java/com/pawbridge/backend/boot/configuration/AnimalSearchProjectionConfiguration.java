package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.animal.internal.command.infrastructure.search.PostgresqlSearchProjector;
import com.pawbridge.backend.animal.internal.discovery.infrastructure.KoreanSearchAnalyzer;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.support.TransactionTemplate;

/** Optional source-to-search repair; never enabled by ordinary reads or commands. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Import(AnimalSearchConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnExpression("'true'.equalsIgnoreCase('${pawbridge.animal-query.projection-schedule-enabled:false}') and "
        + "'postgresql'.equalsIgnoreCase('${pawbridge.animal-query.backend:}') and "
        + "'true'.equalsIgnoreCase('${pawbridge.animal-query.analyzed-text:false}')")
public class AnimalSearchProjectionConfiguration {
    @Bean
    PostgresqlSearchProjector animalSearchProjector(
            @Qualifier("writeDataSource") DataSource source,
            @Qualifier("writeTransactionManager") JdbcTransactionManager manager,
            KoreanSearchAnalyzer analyzer) {
        if (manager.getDataSource() != source)
            throw new IllegalArgumentException("Projection requires the shared writer transaction manager");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(5);
        TransactionTemplate transaction = new TransactionTemplate(manager);
        transaction.setTimeout(5);
        return new PostgresqlSearchProjector(jdbc, transaction, analyzer);
    }

    @Bean
    AnimalSearchProjectionJobs animalSearchProjectionJobs(PostgresqlSearchProjector projector,
            @Value("${pawbridge.animal-query.projection-page-size:50}") int pageSize) {
        return new AnimalSearchProjectionJobs(projector, pageSize);
    }

    @Bean
    @DependsOn({"writeDataSource", "animalDiscoveryAnalyzer", "animalSearchProjectionJobs"})
    ThreadPoolTaskScheduler animalSearchProjectionScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("animal-search-projection-");
        scheduler.setDaemon(true);
        scheduler.setAcceptTasksAfterContextClose(false);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setAwaitTerminationSeconds(15);
        scheduler.setErrorHandler(failure -> org.slf4j.LoggerFactory.getLogger(AnimalSearchProjectionConfiguration.class)
                .warn("Animal projection maintenance failed ({})", failure.getClass().getSimpleName()));
        return scheduler;
    }
}
