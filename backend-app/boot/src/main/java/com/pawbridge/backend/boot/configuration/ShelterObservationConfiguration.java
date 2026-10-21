package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.animal.internal.shelter.infrastructure.JdbcShelterObservationRecorder;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.support.TransactionTemplate;

/** Opt-in original daily observations; ordinary PostgreSQL reads do not enable background writes. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnExpression("'true'.equalsIgnoreCase('${pawbridge.shelter-observations.schedule-enabled:false}') and "
        + "'postgresql'.equalsIgnoreCase('${pawbridge.animal-query.backend:}')")
public class ShelterObservationConfiguration {
    private static final Logger log = LoggerFactory.getLogger(ShelterObservationConfiguration.class);

    @Bean
    JdbcShelterObservationRecorder shelterObservationRecorder(
            @Qualifier("writeDataSource") DataSource source,
            @Qualifier("writeTransactionManager") JdbcTransactionManager manager) {
        if (manager.getDataSource() != source)
            throw new IllegalArgumentException("Shelter observations require the shared writer transaction manager");
        var jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(30);
        var transaction = new TransactionTemplate(manager);
        transaction.setTimeout(30);
        return new JdbcShelterObservationRecorder(jdbc, transaction);
    }

    @Bean
    ShelterObservationJob shelterObservationJob(JdbcShelterObservationRecorder recorder) {
        return new ShelterObservationJob(recorder);
    }

    @Bean(destroyMethod = "shutdown")
    @DependsOn({"writeDataSource", "shelterObservationRecorder"})
    ThreadPoolTaskScheduler shelterObservationScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("shelter-observation-");
        scheduler.setDaemon(true);
        scheduler.setAcceptTasksAfterContextClose(false);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setRemoveOnCancelPolicy(true);
        // Allow normal completion of the original 30-second transaction; this is not a bound on all JDBC I/O.
        scheduler.setAwaitTerminationSeconds(35);
        scheduler.setErrorHandler(failure -> log.warn("Shelter daily observation failed ({})", failure.getClass().getSimpleName()));
        return scheduler;
    }

    public static final class ShelterObservationJob {
        private final JdbcShelterObservationRecorder recorder;

        public ShelterObservationJob(JdbcShelterObservationRecorder recorder) { this.recorder = recorder; }

        @Scheduled(cron = "${pawbridge.shelter-observations.cron:0 0 4 * * *}", zone = "Asia/Seoul",
                scheduler = "shelterObservationScheduler")
        public void record() {
            log.info("Shelter daily observations recorded: {}", recorder.recordToday());
        }
    }
}
