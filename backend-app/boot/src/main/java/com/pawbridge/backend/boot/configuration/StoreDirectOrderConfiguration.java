package com.pawbridge.backend.boot.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.store.api.StoreOrderActors;
import com.pawbridge.backend.store.internal.catalog.infrastructure.KoreanSearchTerms;
import com.pawbridge.backend.store.internal.directorder.application.DirectOrders;
import com.pawbridge.backend.store.internal.directorder.infrastructure.JpaDirectOrders;
import com.pawbridge.backend.store.internal.directorder.web.DirectOrderController;
import com.pawbridge.backend.store.internal.directorder.web.DirectOrderExceptionHandler;
import com.pawbridge.backend.user.internal.storeorder.infrastructure.JdbcStoreOrderActors;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.Arrays;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** One opt-in Store writer unit for direct purchase and the purchaser's order details. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pawbridge.store-direct-orders", name = "enabled", havingValue = "true")
@Import(JpaAuditingConfiguration.class)
public class StoreDirectOrderConfiguration {
    @Bean
    LocalContainerEntityManagerFactoryBean storeDirectOrderEntityManagerFactory(
            @Qualifier("writeDataSource") DataSource source) {
        String[] managed = Arrays.copyOf(StoreMemberJpaConfiguration.MANAGED_TYPES,
                StoreMemberJpaConfiguration.MANAGED_TYPES.length + 1);
        managed[managed.length - 1] = "com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.model.Outbox";
        return StoreMemberJpaConfiguration.newFactory(source, "pawbridge-store-direct-order-writer", managed);
    }

    @Bean
    JpaTransactionManager storeDirectOrderJpaTransactionManager(
            @Qualifier("storeDirectOrderEntityManagerFactory") EntityManagerFactory factory,
            @Qualifier("writeDataSource") DataSource source) {
        var manager = new JpaTransactionManager(factory);
        manager.setDataSource(source);
        manager.setDefaultTimeout(15);
        return manager;
    }

    @Bean
    EntityManager storeDirectOrderEntityManager(
            @Qualifier("storeDirectOrderEntityManagerFactory") EntityManagerFactory factory) {
        return SharedEntityManagerCreator.createSharedEntityManager(factory);
    }

    @Bean
    TransactionTemplate storeDirectOrderWrites(
            @Qualifier("storeDirectOrderJpaTransactionManager") JpaTransactionManager manager) {
        var writes = new TransactionTemplate(manager);
        writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        return writes;
    }

    @Bean
    TransactionTemplate storeDirectOrderReads(
            @Qualifier("storeDirectOrderJpaTransactionManager") JpaTransactionManager manager) {
        var reads = new TransactionTemplate(manager);
        reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        reads.setReadOnly(true);
        return reads;
    }

    @Bean
    StoreOrderActors storeDirectOrderActors(@Qualifier("writeJdbc") JdbcTemplate jdbc,
            @Qualifier("writeDataSource") DataSource source) {
        if (jdbc.getDataSource() != source) throw new IllegalArgumentException("Store order actors must use the same writer DataSource");
        return new JdbcStoreOrderActors(jdbc);
    }

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

    @Bean DirectOrderController directOrderController(DirectOrders orders) { return new DirectOrderController(orders); }
    @Bean DirectOrderExceptionHandler directOrderExceptionHandler() { return new DirectOrderExceptionHandler(); }
}
