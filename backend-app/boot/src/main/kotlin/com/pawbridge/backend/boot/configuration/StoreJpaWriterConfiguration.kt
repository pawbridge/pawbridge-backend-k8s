package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.store.api.StoreOrderActors
import com.pawbridge.backend.user.internal.storeorder.infrastructure.JdbcStoreOrderActors
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import javax.sql.DataSource
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.SharedEntityManagerCreator
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

/** Imported by an explicitly enabled Store writer feature; never registered globally. */
@Configuration(proxyBeanMethods = false)
@Import(JpaAuditingConfiguration::class)
class StoreJpaWriterConfiguration {
    @Bean
    fun storeDirectOrderEntityManagerFactory(
        @Qualifier("writeDataSource") source: DataSource,
        @Value("\${pawbridge.store-carts.enabled:false}") cartFlag: String,
        @Value("\${pawbridge.store-catalog-management.enabled:false}") catalogFlag: String,
        @Value("\${pawbridge.payments.enabled:false}") paymentFlag: String,
    ): LocalContainerEntityManagerFactoryBean {
        val carts = "true".equals(cartFlag, ignoreCase = true) || "true".equals(catalogFlag, ignoreCase = true)
        val payments = "true".equals(paymentFlag, ignoreCase = true)
        val managed = ArrayList<String>(StoreMemberJpaConfiguration.MANAGED_TYPES.asList())
        if (carts) {
            managed.add("com.pawbridge.backend.store.internal.cart.infrastructure.jpa.model.Cart")
            managed.add("com.pawbridge.backend.store.internal.cart.infrastructure.jpa.model.CartItem")
        }
        // Payment rows live in pawbridge_payment (explicit @Table schema) but share this writer unit and manager,
        // so the payment row and the order change commit in one transaction on one connection.
        if (payments) {
            managed.addAll(PAYMENT_TYPES)
        }
        return StoreMemberJpaConfiguration.newFactory(
            source,
            "pawbridge-store-direct-order-writer",
            *managed.toTypedArray(),
        )
    }

    @Bean
    fun storeDirectOrderJpaTransactionManager(
        @Qualifier("storeDirectOrderEntityManagerFactory") factory: EntityManagerFactory,
        @Qualifier("writeDataSource") source: DataSource,
    ): JpaTransactionManager {
        val manager = JpaTransactionManager(factory)
        manager.dataSource = source
        manager.defaultTimeout = 15
        return manager
    }

    @Bean
    fun storeDirectOrderEntityManager(
        @Qualifier("storeDirectOrderEntityManagerFactory") factory: EntityManagerFactory,
    ): EntityManager {
        return SharedEntityManagerCreator.createSharedEntityManager(factory)
    }

    @Bean
    fun storeDirectOrderWrites(
        @Qualifier("storeDirectOrderJpaTransactionManager") manager: JpaTransactionManager,
    ): TransactionTemplate {
        val writes = TransactionTemplate(manager)
        writes.propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRED
        return writes
    }

    @Bean
    fun storeDirectOrderReads(
        @Qualifier("storeDirectOrderJpaTransactionManager") manager: JpaTransactionManager,
    ): TransactionTemplate {
        val reads = TransactionTemplate(manager)
        reads.propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRED
        reads.isReadOnly = true
        return reads
    }

    @Bean
    fun storeDirectOrderActors(
        @Qualifier("writeJdbc") jdbc: JdbcTemplate,
        @Qualifier("writeDataSource") source: DataSource,
    ): StoreOrderActors {
        if (jdbc.dataSource !== source) {
            throw IllegalArgumentException("Store order actors must use the same writer DataSource")
        }
        return JdbcStoreOrderActors(jdbc)
    }

    companion object {
        private const val PAYMENT_MODEL =
            "com.pawbridge.backend.payment.internal.confirmation.infrastructure.jpa.model."

        @JvmField
        val PAYMENT_TYPES: Array<String> = arrayOf(
            PAYMENT_MODEL + "BaseEntity",
            PAYMENT_MODEL + "Payment",
            PAYMENT_MODEL + "PaymentCompensationRecord",
        )
    }
}
