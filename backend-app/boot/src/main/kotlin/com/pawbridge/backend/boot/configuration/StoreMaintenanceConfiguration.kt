package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.store.internal.catalog.infrastructure.KoreanSearchTerms
import com.pawbridge.backend.store.internal.directorder.infrastructure.JdbcProductSearchRebuild
import javax.sql.DataSource
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.DependsOn
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.util.ErrorHandler

/** Store search rebuild only; both the Store feature and the original rebuild flag must be explicit. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "pawbridge",
    name = ["store-maintenance.enabled", "search.rebuild-enabled"],
    havingValue = "true",
    matchIfMissing = false,
)
@EnableScheduling
class StoreMaintenanceConfiguration {
    @Bean
    fun storeSearchRebuildJdbc(@Qualifier("writeDataSource") source: DataSource): NamedParameterJdbcTemplate {
        // Original PostgresqlSearchDocuments set a 5-second query timeout on its own writer JDBC.
        val jdbc = JdbcTemplate(source)
        jdbc.queryTimeout = 5
        return NamedParameterJdbcTemplate(jdbc)
    }

    @Bean
    fun storeSearchRebuildTransaction(
        @Qualifier("writeTransactionManager") manager: JdbcTransactionManager,
    ): TransactionTemplate {
        // Original rebuildPage used @Transactional(timeout=10).
        val transaction = TransactionTemplate(manager)
        transaction.timeout = 10
        return transaction
    }

    @Bean(destroyMethod = "close")
    fun storeSearchRebuildTerms(): KoreanSearchTerms {
        return KoreanSearchTerms()
    }

    @Bean
    fun storeProductSearchRebuild(
        @Qualifier("storeSearchRebuildJdbc") jdbc: NamedParameterJdbcTemplate,
        @Qualifier("storeSearchRebuildTransaction") transaction: TransactionTemplate,
        @Qualifier("storeSearchRebuildTerms") terms: KoreanSearchTerms,
    ): JdbcProductSearchRebuild {
        return JdbcProductSearchRebuild(jdbc, transaction, terms)
    }

    @Bean
    @DependsOn("writeDataSource")
    fun storeMaintenanceScheduler(): ThreadPoolTaskScheduler {
        val scheduler = ThreadPoolTaskScheduler()
        scheduler.poolSize = 1
        scheduler.setThreadNamePrefix("store-maintenance-")
        scheduler.setAcceptTasksAfterContextClose(false)
        scheduler.setWaitForTasksToCompleteOnShutdown(true)
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false)
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
        scheduler.setRemoveOnCancelPolicy(true)
        scheduler.setAwaitTerminationSeconds(15)
        scheduler.setErrorHandler(
            ErrorHandler { failure ->
                LoggerFactory.getLogger(StoreMaintenanceConfiguration::class.java)
                    .warn("Store maintenance failed ({})", failure.javaClass.simpleName)
            },
        )
        return scheduler
    }

    @Bean
    fun storeSearchRebuildJob(rebuild: JdbcProductSearchRebuild): SearchRebuildJob {
        return SearchRebuildJob(rebuild)
    }

    class SearchRebuildJob(private val rebuild: JdbcProductSearchRebuild) {
        @Scheduled(
            scheduler = "storeMaintenanceScheduler",
            fixedDelayString = "\${pawbridge.search.rebuild-delay-ms:5000}",
        )
        fun rebuild() {
            rebuild.rebuildPage(100)
        }
    }
}
