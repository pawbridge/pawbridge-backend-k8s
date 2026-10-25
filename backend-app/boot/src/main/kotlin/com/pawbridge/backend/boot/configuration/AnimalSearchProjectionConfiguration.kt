package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.animal.internal.command.infrastructure.search.PostgresqlSearchProjector
import com.pawbridge.backend.animal.internal.discovery.infrastructure.KoreanSearchAnalyzer
import javax.sql.DataSource
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.DependsOn
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.util.ErrorHandler

/** Optional source-to-search repair; never enabled by ordinary reads or commands. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Import(AnimalSearchConfiguration::class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnExpression(
    "'true'.equalsIgnoreCase('\${pawbridge.animal-query.projection-schedule-enabled:false}') and " +
        "'postgresql'.equalsIgnoreCase('\${pawbridge.animal-query.backend:}') and " +
        "'true'.equalsIgnoreCase('\${pawbridge.animal-query.analyzed-text:false}')",
)
class AnimalSearchProjectionConfiguration {
    @Bean
    fun animalSearchProjector(
        @Qualifier("writeDataSource") source: DataSource,
        @Qualifier("writeTransactionManager") manager: JdbcTransactionManager,
        analyzer: KoreanSearchAnalyzer,
    ): PostgresqlSearchProjector {
        if (manager.dataSource !== source) {
            throw IllegalArgumentException("Projection requires the shared writer transaction manager")
        }
        val jdbc = JdbcTemplate(source)
        jdbc.queryTimeout = 5
        val transaction = TransactionTemplate(manager)
        transaction.timeout = 5
        return PostgresqlSearchProjector(jdbc, transaction, analyzer)
    }

    @Bean
    fun animalSearchProjectionJobs(
        projector: PostgresqlSearchProjector,
        @Value("\${pawbridge.animal-query.projection-page-size:50}") pageSize: Int,
    ): AnimalSearchProjectionJobs {
        return AnimalSearchProjectionJobs(projector, pageSize)
    }

    @Bean
    @DependsOn("writeDataSource", "animalDiscoveryAnalyzer", "animalSearchProjectionJobs")
    fun animalSearchProjectionScheduler(): ThreadPoolTaskScheduler {
        val scheduler = ThreadPoolTaskScheduler()
        scheduler.poolSize = 1
        scheduler.setThreadNamePrefix("animal-search-projection-")
        scheduler.isDaemon = true
        scheduler.setAcceptTasksAfterContextClose(false)
        scheduler.setWaitForTasksToCompleteOnShutdown(true)
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false)
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
        scheduler.setRemoveOnCancelPolicy(true)
        scheduler.setAwaitTerminationSeconds(15)
        scheduler.setErrorHandler(
            ErrorHandler { failure ->
                LoggerFactory.getLogger(AnimalSearchProjectionConfiguration::class.java)
                    .warn("Animal projection maintenance failed ({})", failure.javaClass.simpleName)
            },
        )
        return scheduler
    }
}
