package com.pawbridge.backend.boot.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.store.api.StoreOrderActors;
import com.pawbridge.backend.store.internal.catalog.infrastructure.KoreanSearchTerms;
import com.pawbridge.backend.store.internal.directorder.application.DirectOrders;
import com.pawbridge.backend.store.internal.directorder.infrastructure.JpaDirectOrders;
import com.pawbridge.backend.store.internal.directorder.web.OrderReadController;
import com.pawbridge.backend.store.internal.directorder.web.DirectOrderExceptionHandler;
import jakarta.persistence.EntityManager;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Imported only by an enabled Direct or Cart feature; one shared facade, GET and advice. */
@Configuration(proxyBeanMethods = false)
@Import(StoreJpaWriterConfiguration.class)
public class StoreOrderSupportConfiguration {
    @Bean
    NamedParameterJdbcTemplate storeDirectOrderSearchJdbc(@Qualifier("writeDataSource") DataSource source) {
        var jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(5);
        return new NamedParameterJdbcTemplate(jdbc);
    }

    @Bean(destroyMethod = "close")
    KoreanSearchTerms storeDirectOrderSearchTerms() { return new KoreanSearchTerms(); }

    @Bean
    DirectOrders storeDirectOrders(@Qualifier("storeDirectOrderEntityManager") EntityManager entityManager,
            @Qualifier("storeDirectOrderWrites") TransactionTemplate writes,
            @Qualifier("storeDirectOrderReads") TransactionTemplate reads, StoreOrderActors actors,
            @Qualifier("storeDirectOrderSearchJdbc") NamedParameterJdbcTemplate search,
            @Qualifier("storeDirectOrderSearchTerms") KoreanSearchTerms terms, ObjectMapper mapper) {
        return new JpaDirectOrders(entityManager, entityManager, writes, reads, actors, search, terms, mapper);
    }

    @Bean OrderReadController orderReadController(DirectOrders orders) { return new OrderReadController(orders); }
    @Bean DirectOrderExceptionHandler directOrderExceptionHandler() { return new DirectOrderExceptionHandler(); }
}
