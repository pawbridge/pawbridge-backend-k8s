package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.store.api.StoreOrderActors
import com.pawbridge.backend.store.internal.catalog.infrastructure.KoreanSearchTerms
import com.pawbridge.backend.store.internal.directorder.application.DirectOrders
import com.pawbridge.backend.store.internal.directorder.infrastructure.JpaDirectOrders
import com.pawbridge.backend.store.internal.directorder.web.DirectOrderExceptionHandler
import com.pawbridge.backend.store.internal.directorder.web.OrderReadController
import jakarta.persistence.EntityManager
import javax.sql.DataSource
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionTemplate

/** Imported only by an enabled Direct or Cart feature; one shared facade, GET and advice. */
@Configuration(proxyBeanMethods = false)
@Import(StoreJpaWriterConfiguration::class)
class StoreOrderSupportConfiguration {
    @Bean
    fun storeDirectOrderSearchJdbc(@Qualifier("writeDataSource") source: DataSource): NamedParameterJdbcTemplate {
        val jdbc = JdbcTemplate(source)
        jdbc.queryTimeout = 5
        return NamedParameterJdbcTemplate(jdbc)
    }

    @Bean(destroyMethod = "close")
    fun storeDirectOrderSearchTerms(): KoreanSearchTerms {
        return KoreanSearchTerms()
    }

    @Bean
    fun storeDirectOrders(
        @Qualifier("storeDirectOrderEntityManager") entityManager: EntityManager,
        @Qualifier("storeDirectOrderWrites") writes: TransactionTemplate,
        @Qualifier("storeDirectOrderReads") reads: TransactionTemplate,
        actors: StoreOrderActors,
        @Qualifier("storeDirectOrderSearchJdbc") search: NamedParameterJdbcTemplate,
        @Qualifier("storeDirectOrderSearchTerms") terms: KoreanSearchTerms,
    ): DirectOrders {
        return JpaDirectOrders(entityManager, entityManager, writes, reads, actors, search, terms)
    }

    @Bean
    fun orderReadController(orders: DirectOrders): OrderReadController {
        return OrderReadController(orders)
    }

    @Bean
    fun directOrderExceptionHandler(): DirectOrderExceptionHandler {
        return DirectOrderExceptionHandler()
    }
}
