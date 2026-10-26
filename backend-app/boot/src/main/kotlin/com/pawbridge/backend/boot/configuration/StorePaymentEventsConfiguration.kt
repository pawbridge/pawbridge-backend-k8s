package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.store.api.StorePaymentEvents
import com.pawbridge.backend.store.internal.catalog.infrastructure.KoreanSearchTerms
import com.pawbridge.backend.store.internal.directorder.infrastructure.JpaStockAdjustments
import com.pawbridge.backend.store.internal.directorder.infrastructure.ProductSnapshots
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository.ProductRepository
import com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.repository.ProductSKURepository
import com.pawbridge.backend.store.internal.payment.infrastructure.JpaStorePaymentEvents
import jakarta.persistence.EntityManager
import java.time.Clock
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

/**
 * Default-off Store payment business called directly by Payment and the mock route;
 * no message transport is registered here.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pawbridge.store-payment-events", name = ["enabled"], havingValue = "true")
@Import(StoreOrderSupportConfiguration::class, StorePaymentTransportConfiguration::class)
class StorePaymentEventsConfiguration {
    @Bean
    fun storePaymentEventClock(): Clock {
        return Clock.systemDefaultZone()
    }

    @Bean
    fun storePaymentEventStocks(
        @Qualifier("storeDirectOrderEntityManager") writer: EntityManager,
        @Qualifier("storeDirectOrderSearchJdbc") search: NamedParameterJdbcTemplate,
        @Qualifier("storeDirectOrderSearchTerms") terms: KoreanSearchTerms,
    ): StorePaymentEvents.StockRestorer {
        val repositories = JpaRepositoryFactory(writer)
        val snapshots = ProductSnapshots(search, terms)
        return SharedWriterStockRestorer(
            JpaStockAdjustments(
                repositories.getRepository(ProductRepository::class.java),
                repositories.getRepository(ProductSKURepository::class.java),
                snapshots,
            ),
        )
    }

    @Bean
    fun storePaymentEventRanking(
        @Qualifier("storePaymentRankingRedis") sharedRedis: ObjectProvider<StringRedisTemplate>,
    ): StorePaymentEvents.Ranking {
        // Reuse an explicitly named existing/shared resource; do not allocate a second Redis client here.
        return StorePaymentEvents.Ranking { key, productId, quantity ->
            val redis = sharedRedis.getIfAvailable()
            if (redis == null) {
                throw IllegalStateException("Explicit Store payment ranking Redis resource is required")
            }
            redis.opsForZSet().incrementScore(key, productId, quantity)
        }
    }

    @Bean
    fun storePaymentEvents(
        @Qualifier("storeDirectOrderEntityManager") writer: EntityManager,
        @Qualifier("storeDirectOrderWrites") writes: TransactionTemplate,
        @Qualifier("storeDirectOrderJpaTransactionManager") manager: JpaTransactionManager,
        @Qualifier("storePaymentEventStocks") stocks: StorePaymentEvents.StockRestorer,
        @Qualifier("storePaymentEventRanking") ranking: StorePaymentEvents.Ranking,
        @Qualifier("storePaymentEventClock") clock: Clock,
    ): StorePaymentEvents {
        // completePayment/failPayment must join the caller's Store writer transaction (Payment owns it).
        val mandatory = TransactionTemplate(manager)
        mandatory.propagationBehavior = TransactionDefinition.PROPAGATION_MANDATORY
        return JpaStorePaymentEvents(writer, writes, mandatory, stocks, ranking, clock)
    }

    internal class SharedWriterStockRestorer(val delegate: JpaStockAdjustments) : StorePaymentEvents.StockRestorer {
        override fun increaseStocks(quantitiesBySkuId: Map<Long?, Int>) {
            delegate.increaseStocks(quantitiesBySkuId)
        }
    }
}
