package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.animal.internal.shelter.infrastructure.JdbcShelterObservationRecorder
import javax.sql.DataSource
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
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

/** Opt-in original daily observations; ordinary PostgreSQL reads do not enable background writes. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnExpression(
    "'true'.equalsIgnoreCase('\${pawbridge.shelter-observations.schedule-enabled:false}') and " +
        "'postgresql'.equalsIgnoreCase('\${pawbridge.animal-query.backend:}')",
)
class ShelterObservationConfiguration {
    @Bean
    fun shelterObservationRecorder(
        @Qualifier("writeDataSource") source: DataSource,
        @Qualifier("writeTransactionManager") manager: JdbcTransactionManager,
    ): JdbcShelterObservationRecorder {
        if (manager.dataSource !== source) {
            throw IllegalArgumentException("Shelter observations require the shared writer transaction manager")
        }
        val jdbc = JdbcTemplate(source)
        jdbc.queryTimeout = 30
        val transaction = TransactionTemplate(manager)
        transaction.timeout = 30
        return JdbcShelterObservationRecorder(jdbc, transaction)
    }

    @Bean
    fun shelterObservationJob(recorder: JdbcShelterObservationRecorder): ShelterObservationJob {
        return ShelterObservationJob(recorder)
    }

    @Bean(destroyMethod = "shutdown")
    @DependsOn("writeDataSource", "shelterObservationRecorder")
    fun shelterObservationScheduler(): ThreadPoolTaskScheduler {
        val scheduler = ThreadPoolTaskScheduler()
        scheduler.poolSize = 1
        scheduler.setThreadNamePrefix("shelter-observation-")
        scheduler.isDaemon = true
        scheduler.setAcceptTasksAfterContextClose(false)
        scheduler.setWaitForTasksToCompleteOnShutdown(true)
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false)
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
        scheduler.setRemoveOnCancelPolicy(true)
        // Allow normal completion of the original 30-second transaction; this is not a bound on all JDBC I/O.
        scheduler.setAwaitTerminationSeconds(35)
        scheduler.setErrorHandler(
            ErrorHandler { failure -> log.warn("Shelter daily observation failed ({})", failure.javaClass.simpleName) },
        )
        return scheduler
    }

    class ShelterObservationJob(private val recorder: JdbcShelterObservationRecorder) {
        @Scheduled(
            cron = "\${pawbridge.shelter-observations.cron:0 0 4 * * *}",
            zone = "Asia/Seoul",
            scheduler = "shelterObservationScheduler",
        )
        fun record() {
            log.info("Shelter daily observations recorded: {}", recorder.recordToday())
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(ShelterObservationConfiguration::class.java)
    }
}
