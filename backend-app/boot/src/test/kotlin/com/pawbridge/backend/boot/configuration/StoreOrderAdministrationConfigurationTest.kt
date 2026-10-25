package com.pawbridge.backend.boot.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.store.api.StoreOrderAdministrators
import com.pawbridge.backend.store.internal.cart.web.CartController
import com.pawbridge.backend.store.internal.catalog.infrastructure.KoreanSearchTerms
import com.pawbridge.backend.store.internal.directorder.web.DirectOrderController
import com.pawbridge.backend.store.internal.directorder.web.OrderReadController
import com.pawbridge.backend.store.internal.orderadministration.application.AdminOrders
import com.pawbridge.backend.store.internal.orderadministration.web.AdminOrderController
import com.pawbridge.backend.store.internal.orderadministration.web.AdminOrderExceptionHandler
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.data.auditing.AuditingHandler
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier
import javax.sql.DataSource

class StoreOrderAdministrationConfigurationTest {
    @ParameterizedTest
    @ValueSource(strings = ["absent", "false", "yes", "on", "1", "unknown"])
    fun given__administration_not_explicitly_enabled__when__assembling_without_database_inputs__then__register_no_admin_or_writer_beans(
        flag: String,
    ) {
        var runner = runner()
        if (flag != "absent") {
            runner = runner.withPropertyValues("pawbridge.store-order-administration.enabled=$flag")
        }
        runner.run { context ->
            assertThat(context).hasNotFailed()
                .doesNotHaveBean(AdminOrders::class.java)
                .doesNotHaveBean(StoreOrderAdministrators::class.java)
                .doesNotHaveBean(AdminOrderController::class.java)
                .doesNotHaveBean(EntityManagerFactory::class.java)
                .doesNotHaveBean(StoreJpaWriterConfiguration::class.java)
                .doesNotHaveBean(AuditingHandler::class.java)
                .doesNotHaveBean(KoreanSearchTerms::class.java)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["admin", "admin-and-direct", "all"])
    fun given__explicit_admin_and_optional_personal_features__when__assembling__then__share_one_writer_without_exposing_disabled_routes(
        mode: String,
    ) {
        val direct = mode != "admin"
        val carts = mode == "all"
        val writer: DataSource = mock(DataSource::class.java)
        val reader: DataSource = mock(DataSource::class.java)
        val readerManager: PlatformTransactionManager = mock(PlatformTransactionManager::class.java)
        val unit = AtomicReference<EntityManagerFactory>()
        runner().withBean("writeDataSource", DataSource::class.java, Supplier { writer })
            .withBean("writeJdbc", JdbcTemplate::class.java, Supplier { JdbcTemplate(writer) })
            .withBean("dataSource", DataSource::class.java, Supplier { reader })
            .withBean("transactionManager", PlatformTransactionManager::class.java, Supplier { readerManager })
            .withBean(ObjectMapper::class.java, Supplier { ObjectMapper() })
            .withPropertyValues(
                "pawbridge.store-order-administration.enabled=TrUe",
                "pawbridge.store-direct-orders.enabled=$direct",
                "pawbridge.store-carts.enabled=$carts",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                    .hasSingleBean(AdminOrders::class.java)
                    .hasSingleBean(AdminOrderController::class.java)
                    .hasSingleBean(AdminOrderExceptionHandler::class.java)
                    .hasSingleBean(StoreOrderAdministrators::class.java)
                    .hasSingleBean(StoreJpaWriterConfiguration::class.java)
                    .hasSingleBean(EntityManagerFactory::class.java)
                    .hasSingleBean(JpaTransactionManager::class.java)
                    .hasSingleBean(AuditingHandler::class.java)
                unit.set(context.getBean("storeDirectOrderEntityManagerFactory", EntityManagerFactory::class.java))
                val factory = context.getBean(
                    "&storeDirectOrderEntityManagerFactory",
                    LocalContainerEntityManagerFactoryBean::class.java,
                )
                assertThat(factory.dataSource).isSameAs(writer).isNotSameAs(reader)
                assertThat(factory.persistenceUnitInfo!!.managedClassNames).hasSize(if (carts) 12 else 10)
                assertThat(factory.jpaPropertyMap)
                    .containsEntry("hibernate.boot.allow_jdbc_metadata_access", false)
                    .containsEntry("hibernate.hbm2ddl.auto", "none")
                    .containsEntry("hibernate.default_schema", "pawbridge_store")
                val manager = context.getBean("storeDirectOrderJpaTransactionManager", JpaTransactionManager::class.java)
                assertThat(manager.dataSource).isSameAs(writer)
                assertThat(manager.defaultTimeout).isEqualTo(15)
                val reads = context.getBean("storeDirectOrderReads", TransactionTemplate::class.java)
                val writes = context.getBean("storeDirectOrderWrites", TransactionTemplate::class.java)
                assertThat(reads.isReadOnly).isTrue()
                assertThat(writes.isReadOnly).isFalse()
                for (tx in listOf(reads, writes)) {
                    assertThat(tx.transactionManager).isSameAs(manager).isNotSameAs(readerManager)
                    assertThat(tx.propagationBehavior).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED)
                }
                val jdbc = ReflectionTestUtils.getField(context.getBean(StoreOrderAdministrators::class.java), "jdbc")
                    as JdbcTemplate
                assertThat(jdbc.dataSource).isSameAs(writer)
                assertThat(context.getBeansOfType(DirectOrderController::class.java)).hasSize(if (direct) 1 else 0)
                assertThat(context.getBeansOfType(CartController::class.java)).hasSize(if (carts) 1 else 0)
                assertThat(context.getBeansOfType(OrderReadController::class.java))
                    .hasSize(if (direct || carts) 1 else 0)
                assertThat(context.getBeansOfType(KoreanSearchTerms::class.java)).hasSize(if (direct || carts) 1 else 0)
                assertThat(context.getBeansOfType(StoreOrderSupportConfiguration::class.java))
                    .hasSize(if (direct || carts) 1 else 0)
                val shared = context.getBean("storeDirectOrderEntityManager", EntityManager::class.java)
                assertThat(shared.entityManagerFactory).isSameAs(unit.get())
                verifyNoInteractions(writer, reader, readerManager)
            }
        assertThat(unit.get().isOpen).isFalse()
    }

    @ParameterizedTest
    @ValueSource(strings = ["reader-only", "mismatched-jdbc"])
    fun given__no_matching_writer__when__administration_is_enabled__then__fail_without_reader_fallback(mode: String) {
        val writer: DataSource = mock(DataSource::class.java)
        val reader: DataSource = mock(DataSource::class.java)
        var runner = runner().withPropertyValues("pawbridge.store-order-administration.enabled=true")
        runner = if (mode == "reader-only") {
            runner.withBean("dataSource", DataSource::class.java, Supplier { reader })
        } else {
            runner.withBean("writeDataSource", DataSource::class.java, Supplier { writer })
                .withBean("writeJdbc", JdbcTemplate::class.java, Supplier { JdbcTemplate(reader) })
        }
        runner.run { context ->
            assertThat(context).hasFailed()
            verifyNoInteractions(writer, reader)
        }
    }

    private fun runner(): ApplicationContextRunner {
        return ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration::class.java))
            .withUserConfiguration(
                StoreOrderAdministrationConfiguration::class.java,
                StoreDirectOrderConfiguration::class.java,
                StoreCartConfiguration::class.java,
            )
    }
}
