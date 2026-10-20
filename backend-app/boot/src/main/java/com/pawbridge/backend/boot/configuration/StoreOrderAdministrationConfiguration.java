package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.store.api.StoreOrderAdministrators;
import com.pawbridge.backend.store.internal.orderadministration.application.AdminOrders;
import com.pawbridge.backend.store.internal.orderadministration.infrastructure.JpaAdminOrders;
import com.pawbridge.backend.store.internal.orderadministration.web.AdminOrderController;
import com.pawbridge.backend.store.internal.orderadministration.web.AdminOrderExceptionHandler;
import com.pawbridge.backend.user.internal.storeorder.infrastructure.JdbcStoreOrderAdministrators;
import jakarta.persistence.EntityManager;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Administrator routes reuse the writer without importing personal order routes or search resources. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="pawbridge.store-order-administration",name="enabled",havingValue="true")
@Import(StoreJpaWriterConfiguration.class)
public class StoreOrderAdministrationConfiguration {
    @Bean
    StoreOrderAdministrators storeOrderAdministrators(@Qualifier("writeJdbc") JdbcTemplate jdbc,
            @Qualifier("writeDataSource") DataSource source) {
        if(jdbc.getDataSource()!=source) throw new IllegalArgumentException("Store administrators must use the same writer DataSource");
        return new JdbcStoreOrderAdministrators(jdbc);
    }
    @Bean
    AdminOrders storeAdminOrders(@Qualifier("storeDirectOrderEntityManager") EntityManager entityManager,
            @Qualifier("storeDirectOrderWrites") TransactionTemplate writes,
            @Qualifier("storeDirectOrderReads") TransactionTemplate reads,StoreOrderAdministrators administrators) {
        return new JpaAdminOrders(entityManager,writes,reads,administrators);
    }
    @Bean AdminOrderController adminOrderController(AdminOrders orders){return new AdminOrderController(orders);}
    @Bean AdminOrderExceptionHandler adminOrderExceptionHandler(){return new AdminOrderExceptionHandler();}
}
