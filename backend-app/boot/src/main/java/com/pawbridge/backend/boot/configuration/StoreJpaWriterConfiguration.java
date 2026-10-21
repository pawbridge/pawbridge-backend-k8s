package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.store.api.StoreOrderActors;
import com.pawbridge.backend.user.internal.storeorder.infrastructure.JdbcStoreOrderActors;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.Arrays;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Imported by an explicitly enabled Store writer feature; never registered globally. */
@Configuration(proxyBeanMethods = false)
@Import(JpaAuditingConfiguration.class)
public class StoreJpaWriterConfiguration {
    @Bean
    LocalContainerEntityManagerFactoryBean storeDirectOrderEntityManagerFactory(
            @Qualifier("writeDataSource") DataSource source,
            @Value("${pawbridge.store-carts.enabled:false}") String cartFlag,
            @Value("${pawbridge.store-catalog-management.enabled:false}") String catalogFlag) {
        boolean carts = "true".equalsIgnoreCase(cartFlag) || "true".equalsIgnoreCase(catalogFlag);
        String[] managed = Arrays.copyOf(StoreMemberJpaConfiguration.MANAGED_TYPES,
                StoreMemberJpaConfiguration.MANAGED_TYPES.length + (carts ? 3 : 1));
        int next = StoreMemberJpaConfiguration.MANAGED_TYPES.length;
        managed[next++] = "com.pawbridge.backend.store.internal.directorder.infrastructure.jpa.model.Outbox";
        if (carts) {
            managed[next++] = "com.pawbridge.backend.store.internal.cart.infrastructure.jpa.model.Cart";
            managed[next] = "com.pawbridge.backend.store.internal.cart.infrastructure.jpa.model.CartItem";
        }
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
}
