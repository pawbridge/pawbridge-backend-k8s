package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.store.api.StoreOrderAdministrators
import com.pawbridge.backend.store.internal.orderadministration.application.AdminOrders
import com.pawbridge.backend.store.internal.orderadministration.infrastructure.JpaAdminOrders
import com.pawbridge.backend.store.internal.orderadministration.web.AdminOrderController
import com.pawbridge.backend.store.internal.orderadministration.web.AdminOrderExceptionHandler
import com.pawbridge.backend.user.internal.storeorder.infrastructure.JdbcStoreOrderAdministrators
import jakarta.persistence.EntityManager
import javax.sql.DataSource
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate

/** Administrator routes reuse the writer without importing personal order routes or search resources. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pawbridge.store-order-administration", name = ["enabled"], havingValue = "true")
@Import(StoreJpaWriterConfiguration::class)
class StoreOrderAdministrationConfiguration {
    @Bean
    fun storeOrderAdministrators(
        @Qualifier("writeJdbc") jdbc: JdbcTemplate,
        @Qualifier("writeDataSource") source: DataSource,
    ): StoreOrderAdministrators {
        if (jdbc.dataSource !== source) {
            throw IllegalArgumentException("Store administrators must use the same writer DataSource")
        }
        return JdbcStoreOrderAdministrators(jdbc)
    }

    @Bean
    fun storeAdminOrders(
        @Qualifier("storeDirectOrderEntityManager") entityManager: EntityManager,
        @Qualifier("storeDirectOrderWrites") writes: TransactionTemplate,
        @Qualifier("storeDirectOrderReads") reads: TransactionTemplate,
        administrators: StoreOrderAdministrators,
    ): AdminOrders {
        return JpaAdminOrders(entityManager, writes, reads, administrators)
    }

    @Bean
    fun adminOrderController(orders: AdminOrders): AdminOrderController {
        return AdminOrderController(orders)
    }

    @Bean
    fun adminOrderExceptionHandler(): AdminOrderExceptionHandler {
        return AdminOrderExceptionHandler()
    }
}
