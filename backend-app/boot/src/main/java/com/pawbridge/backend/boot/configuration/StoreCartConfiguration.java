package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.store.api.StoreOrderActors;
import com.pawbridge.backend.store.internal.catalog.infrastructure.KoreanSearchTerms;
import com.pawbridge.backend.store.internal.directorder.application.CartOrders;
import com.pawbridge.backend.store.internal.directorder.infrastructure.JpaCartOrders;
import com.pawbridge.backend.store.internal.directorder.web.CartOrderController;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import com.pawbridge.backend.store.internal.cart.application.Carts;
import com.pawbridge.backend.store.internal.cart.infrastructure.JpaCarts;
import com.pawbridge.backend.store.internal.cart.web.CartController;
import com.pawbridge.backend.store.internal.cart.web.CartExceptionHandler;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

/** Opt-in cart management on the same Store writer used by direct orders. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pawbridge.store-carts", name = "enabled", havingValue = "true")
@Import(StoreOrderSupportConfiguration.class)
public class StoreCartConfiguration {
    @Bean
    Carts storeCarts(@Qualifier("storeDirectOrderEntityManager") EntityManager entityManager,
            @Qualifier("storeDirectOrderWrites") TransactionTemplate writes,
            @Qualifier("storeDirectOrderReads") TransactionTemplate reads, StoreOrderActors actors) {
        return new JpaCarts(entityManager, writes, reads, actors);
    }

    @Bean
    CartOrders storeCartOrders(@Qualifier("storeDirectOrderEntityManager") EntityManager entityManager,
            @Qualifier("storeDirectOrderWrites") TransactionTemplate writes, StoreOrderActors actors,
            @Qualifier("storeDirectOrderSearchJdbc") NamedParameterJdbcTemplate search,
            @Qualifier("storeDirectOrderSearchTerms") KoreanSearchTerms terms) {
        return new JpaCartOrders(entityManager, writes, actors, search, terms);
    }

    @Bean CartOrderController cartOrderController(CartOrders orders) { return new CartOrderController(orders); }
    @Bean CartController cartController(Carts carts) { return new CartController(carts); }
    @Bean CartExceptionHandler cartExceptionHandler() { return new CartExceptionHandler(); }
}
