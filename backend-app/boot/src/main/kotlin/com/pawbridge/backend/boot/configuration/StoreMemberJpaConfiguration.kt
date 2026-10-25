package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.store.api.StoreMemberReads
import com.pawbridge.backend.store.internal.memberread.infrastructure.JdbcStoreMemberReads
import com.pawbridge.backend.store.internal.memberread.infrastructure.JpaStoreMemberReads
import jakarta.persistence.EntityManagerFactory
import javax.sql.DataSource
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.SharedEntityManagerCreator
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

/** Imported only through the enabled MyPage feature; no global entity or repository scan. */
@Configuration(proxyBeanMethods = false)
internal class StoreMemberJpaConfiguration {
    @Bean
    fun storeMemberEntityManagerFactory(
        @Qualifier("dataSource") source: DataSource,
    ): LocalContainerEntityManagerFactoryBean {
        return newFactory(source, "pawbridge-store-member-reader", *MANAGED_TYPES)
    }

    @Bean
    fun storeMemberJpaTransactionManager(
        @Qualifier("storeMemberEntityManagerFactory") factory: EntityManagerFactory,
        @Qualifier("dataSource") source: DataSource,
    ): JpaTransactionManager {
        val manager = JpaTransactionManager(factory)
        manager.dataSource = source
        return manager
    }

    @Bean
    fun storeMemberReads(
        @Qualifier("storeMemberEntityManagerFactory") factory: EntityManagerFactory,
        @Qualifier("storeMemberJpaTransactionManager") manager: JpaTransactionManager,
        @Qualifier("userStoreModuleJdbc") jdbc: JdbcTemplate,
        @Qualifier("userStoreModuleReadTransaction") cartReads: TransactionTemplate,
    ): StoreMemberReads {
        val reads = TransactionTemplate(manager)
        reads.isReadOnly = true
        reads.isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        reads.timeout = 5
        return JpaStoreMemberReads(
            SharedEntityManagerCreator.createSharedEntityManager(factory),
            reads,
            JdbcStoreMemberReads(NamedParameterJdbcTemplate(jdbc), cartReads),
        )
    }

    companion object {
        const val MODEL = "com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model."

        @JvmField
        val MANAGED_TYPES: Array<String> = arrayOf(
            MODEL + "BaseEntity",
            MODEL + "Wishlist",
            MODEL + "ProductSKU",
            MODEL + "Product",
            MODEL + "Category",
            MODEL + "SKUValue",
            MODEL + "OptionValue",
            MODEL + "OptionGroup",
            MODEL + "Order",
            MODEL + "OrderItem",
        )

        @JvmStatic
        fun newFactory(
            source: DataSource,
            unitName: String,
            vararg managedClassNames: String,
        ): LocalContainerEntityManagerFactoryBean {
            val factory = LocalContainerEntityManagerFactoryBean()
            factory.setDataSource(source)
            factory.setPersistenceUnitName(unitName)
            factory.setManagedTypes(PersistenceManagedTypes.of(*managedClassNames))
            factory.jpaVendorAdapter = HibernateJpaVendorAdapter()
            factory.setJpaPropertyMap(
                mapOf(
                    "hibernate.dialect" to "org.hibernate.dialect.PostgreSQLDialect",
                    "hibernate.boot.allow_jdbc_metadata_access" to false,
                    "hibernate.hbm2ddl.auto" to "none",
                    "jakarta.persistence.schema-generation.database.action" to "none",
                    "hibernate.default_schema" to "pawbridge_store",
                    "hibernate.jdbc.time_zone" to "UTC",
                    "hibernate.physical_naming_strategy" to "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy",
                ),
            )
            return factory
        }
    }
}
