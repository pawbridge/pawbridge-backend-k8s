package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.store.internal.catalog.application.StoreCatalog
import com.pawbridge.backend.store.internal.catalog.infrastructure.JdbcStoreCatalog
import com.pawbridge.backend.store.internal.catalog.web.StoreCatalogController
import com.pawbridge.backend.store.internal.catalog.web.StoreCatalogExceptionHandler
import javax.sql.DataSource
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/** Activation requires the Store reader privileges and routing ownership to be handed over. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pawbridge.store-catalog", name = ["enabled"], havingValue = "true", matchIfMissing = false)
class StoreCatalogConfiguration {
    @Bean
    fun storeCatalogJdbc(@Qualifier("dataSource") source: DataSource): JdbcTemplate {
        val jdbc = JdbcTemplate(source)
        jdbc.queryTimeout = 5
        return jdbc
    }

    @Bean
    fun storeCatalogReadTransaction(
        @Qualifier("transactionManager") manager: JdbcTransactionManager,
    ): TransactionTemplate {
        val reads = TransactionTemplate(manager)
        reads.isReadOnly = true
        return reads
    }

    @Bean(destroyMethod = "close")
    fun storeCatalog(
        @Qualifier("storeCatalogJdbc") jdbc: JdbcTemplate,
        @Qualifier("storeCatalogReadTransaction") reads: TransactionTemplate,
    ): JdbcStoreCatalog {
        return JdbcStoreCatalog(NamedParameterJdbcTemplate(jdbc), reads)
    }

    @Bean
    fun storeCatalogController(catalog: StoreCatalog): StoreCatalogController {
        return StoreCatalogController(catalog)
    }

    @Bean
    fun storeCatalogExceptionHandler(): StoreCatalogExceptionHandler {
        return StoreCatalogExceptionHandler()
    }
}
