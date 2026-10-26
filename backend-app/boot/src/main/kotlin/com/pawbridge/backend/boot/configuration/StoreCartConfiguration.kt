package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.store.api.StoreOrderActors
import com.pawbridge.backend.store.internal.cart.application.Carts
import com.pawbridge.backend.store.internal.cart.infrastructure.JpaCarts
import com.pawbridge.backend.store.internal.cart.web.CartController
import com.pawbridge.backend.store.internal.cart.web.CartExceptionHandler
import com.pawbridge.backend.store.internal.catalog.infrastructure.KoreanSearchTerms
import com.pawbridge.backend.store.internal.directorder.application.CartOrders
import com.pawbridge.backend.store.internal.directorder.infrastructure.JpaCartOrders
import com.pawbridge.backend.store.internal.directorder.web.CartOrderController
import jakarta.persistence.EntityManager
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionTemplate

/** Opt-in cart management on the same Store writer used by direct orders. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pawbridge.store-carts", name = ["enabled"], havingValue = "true")
@Import(StoreOrderSupportConfiguration::class)
class StoreCartConfiguration {
    @Bean
    fun storeCarts(
        @Qualifier("storeDirectOrderEntityManager") entityManager: EntityManager,
        @Qualifier("storeDirectOrderWrites") writes: TransactionTemplate,
        @Qualifier("storeDirectOrderReads") reads: TransactionTemplate,
        actors: StoreOrderActors,
    ): Carts {
        return JpaCarts(entityManager, writes, reads, actors)
    }

    @Bean
    fun storeCartOrders(
        @Qualifier("storeDirectOrderEntityManager") entityManager: EntityManager,
        @Qualifier("storeDirectOrderWrites") writes: TransactionTemplate,
        actors: StoreOrderActors,
        @Qualifier("storeDirectOrderSearchJdbc") search: NamedParameterJdbcTemplate,
        @Qualifier("storeDirectOrderSearchTerms") terms: KoreanSearchTerms,
    ): CartOrders {
        return JpaCartOrders(entityManager, writes, actors, search, terms)
    }

    @Bean
    fun cartOrderController(orders: CartOrders): CartOrderController {
        return CartOrderController(orders)
    }

    @Bean
    fun cartController(carts: Carts): CartController {
        return CartController(carts)
    }

    @Bean
    fun cartExceptionHandler(): CartExceptionHandler {
        return CartExceptionHandler()
    }
}
