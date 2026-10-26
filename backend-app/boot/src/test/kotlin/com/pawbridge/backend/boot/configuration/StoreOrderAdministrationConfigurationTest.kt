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
import io.mockk.confirmVerified
import io.mockk.excludeRecords
import io.mockk.mockk
import jakarta.persistence.EntityManagerFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.data.auditing.AuditingHandler
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier
import javax.sql.DataSource

class StoreOrderAdministrationConfigurationTest {
    @ParameterizedTest
    @ValueSource(strings = ["absent", "false", "yes", "on", "1", "unknown"])
    fun `관리가 명시적으로 켜지지 않았을 때__DB 입력 없이 조립하면__관리·쓰기 빈을 등록하지 않는다`(
        flag: String,
    ) {
        // given
        var runner = runner()
        if (flag != "absent") {
            runner = runner.withPropertyValues("pawbridge.store-order-administration.enabled=$flag")
        }

        // when
        runner.run { context ->
            // then
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
    fun `관리와 선택 기능이 명시됐을 때__조립하면__꺼진 라우트는 열지 않고 읽기 자원을 건드리지 않는다`(
        mode: String,
    ) {
        val direct = mode != "admin"
        val carts = mode == "all"
        // given
        val writer = mockk<DataSource>()
        val reader = mockk<DataSource>()
        val readerManager = mockk<PlatformTransactionManager>()

        excludeRecords {
            writer.hashCode()
            reader.hashCode()
            readerManager.hashCode()
        }

        val unit = AtomicReference<EntityManagerFactory>()

        // when
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
                // then
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
                assertThat(factory.persistenceUnitInfo!!.managedClassNames).hasSize(if (carts) 12 else 10)
                assertThat(factory.jpaPropertyMap)
                    .containsEntry("hibernate.boot.allow_jdbc_metadata_access", false)
                    .containsEntry("hibernate.hbm2ddl.auto", "none")
                    .containsEntry("hibernate.default_schema", "pawbridge_store")
                val manager = context.getBean("storeDirectOrderJpaTransactionManager", JpaTransactionManager::class.java)
                assertThat(manager.defaultTimeout).isEqualTo(15)
                val reads = context.getBean("storeDirectOrderReads", TransactionTemplate::class.java)
                val writes = context.getBean("storeDirectOrderWrites", TransactionTemplate::class.java)
                assertThat(reads.isReadOnly).isTrue()
                assertThat(writes.isReadOnly).isFalse()
                for (tx in listOf(reads, writes)) {
                    assertThat(tx.propagationBehavior).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED)
                }
                assertThat(context.getBeansOfType(DirectOrderController::class.java)).hasSize(if (direct) 1 else 0)
                assertThat(context.getBeansOfType(CartController::class.java)).hasSize(if (carts) 1 else 0)
                assertThat(context.getBeansOfType(OrderReadController::class.java))
                    .hasSize(if (direct || carts) 1 else 0)
                assertThat(context.getBeansOfType(KoreanSearchTerms::class.java)).hasSize(if (direct || carts) 1 else 0)
                assertThat(context.getBeansOfType(StoreOrderSupportConfiguration::class.java))
                    .hasSize(if (direct || carts) 1 else 0)
                confirmVerified(writer, reader, readerManager)
            }
        assertThat(unit.get().isOpen).isFalse()
    }

    @ParameterizedTest
    @ValueSource(strings = ["reader-only", "mismatched-jdbc"])
    fun `맞는 쓰기 DB가 없을 때__관리를 켜면__리더로 대체하지 않고 실패한다`(mode: String) {
        // given
        val writer = mockk<DataSource>()
        val reader = mockk<DataSource>()

        excludeRecords {
            writer.hashCode()
            reader.hashCode()
        }

        var runner = runner().withPropertyValues("pawbridge.store-order-administration.enabled=true")
        runner = if (mode == "reader-only") {
            runner.withBean("dataSource", DataSource::class.java, Supplier { reader })
        } else {
            runner.withBean("writeDataSource", DataSource::class.java, Supplier { writer })
                .withBean("writeJdbc", JdbcTemplate::class.java, Supplier { JdbcTemplate(reader) })
        }

        // when
        runner.run { context ->
            // then
            assertThat(context).hasFailed()
            confirmVerified(writer, reader)
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
