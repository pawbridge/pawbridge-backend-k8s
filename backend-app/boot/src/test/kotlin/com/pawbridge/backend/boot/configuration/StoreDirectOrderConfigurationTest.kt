package com.pawbridge.backend.boot.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.internal.command.infrastructure.service.AnimalCommandService
import com.pawbridge.backend.store.api.StoreMemberReads
import com.pawbridge.backend.store.api.StoreOrderActors
import com.pawbridge.backend.store.internal.catalog.application.StoreCatalog
import com.pawbridge.backend.store.internal.catalog.infrastructure.KoreanSearchTerms
import com.pawbridge.backend.store.internal.directorder.application.DirectOrders
import com.pawbridge.backend.store.internal.directorder.web.DirectOrderController
import com.pawbridge.backend.store.internal.directorder.web.DirectOrderExceptionHandler
import com.pawbridge.backend.store.internal.directorder.web.OrderReadController
import io.mockk.confirmVerified
import io.mockk.excludeRecords
import io.mockk.mockk
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.data.auditing.AuditingHandler
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.test.util.AopTestUtils
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier
import javax.sql.DataSource

class StoreDirectOrderConfigurationTest {
    @ParameterizedTest
    @ValueSource(strings = ["absent", "false", "yes", "on", "1", "unknown"])
    fun `주문이 명시적 true가 아닐 때__DB 입력 없이 조립하면__주문·감사·쓰기 빈을 만들지 않는다`(
        flag: String,
    ) {
        // given
        var runner = runner()
        if (flag != "absent") {
            runner = runner.withPropertyValues("pawbridge.store-direct-orders.enabled=$flag")
        }

        // when
        runner.run { context ->
            // then
            assertThat(context).hasNotFailed()
                .doesNotHaveBean(EntityManagerFactory::class.java)
                .doesNotHaveBean(DirectOrders::class.java)
                .doesNotHaveBean(StoreOrderActors::class.java)
                .doesNotHaveBean(DirectOrderController::class.java)
                .doesNotHaveBean(OrderReadController::class.java)
                .doesNotHaveBean(DirectOrderExceptionHandler::class.java)
                .doesNotHaveBean(KoreanSearchTerms::class.java)
                .doesNotHaveBean(AuditingHandler::class.java)
                .doesNotHaveBean(StoreMemberReads::class.java)
                .doesNotHaveBean(StoreCatalog::class.java)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["store", "animal", "both"])
    fun `쓰기 기능이 선택됐을 때__조립하면__감사 등록 하나를 공유하고 스토어는 단일 쓰기 유닛을 쓴다`(
        mode: String,
    ) {
        // given
        val writer = mockk<DataSource>()
        val reader = mockk<DataSource>()
        val readerManager = mockk<PlatformTransactionManager>()

        excludeRecords {
            writer.hashCode()
            reader.hashCode()
            readerManager.hashCode()
        }

        val units = AtomicReference<List<EntityManagerFactory>>()
        val terms = AtomicReference<KoreanSearchTerms>()
        var runner = writerInputs(writer).withBean("dataSource", DataSource::class.java, Supplier { reader })
            .withBean("transactionManager", PlatformTransactionManager::class.java, Supplier { readerManager })
        if (mode != "animal") {
            runner = runner.withPropertyValues("pawbridge.store-direct-orders.enabled=TrUe")
        }
        if (mode != "store") {
            runner = runner.withPropertyValues("pawbridge.animal-commands.enabled=true")
        }

        // when
        runner.run { context ->
            // then
            assertThat(context).hasNotFailed()
                .hasSingleBean(JpaAuditingConfiguration::class.java)
                .hasSingleBean(AuditingHandler::class.java)
                .doesNotHaveBean(StoreMemberReads::class.java)
                .doesNotHaveBean(StoreCatalog::class.java)
            units.set(context.getBeansOfType(EntityManagerFactory::class.java).values.toList())
            assertThat(units.get()).hasSize(if (mode == "both") 2 else 1)
            assertThat(context.containsBean("animalCommandService")).isEqualTo(mode != "store")
            if (mode == "animal") {
                assertThat(context)
                    .doesNotHaveBean(DirectOrders::class.java)
                    .doesNotHaveBean(OrderReadController::class.java)
                    .doesNotHaveBean(KoreanSearchTerms::class.java)
            } else {
                val emf = context.getBean("storeDirectOrderEntityManagerFactory", EntityManagerFactory::class.java)
                val factory = context.getBean(
                    "&storeDirectOrderEntityManagerFactory",
                    LocalContainerEntityManagerFactoryBean::class.java,
                )
                assertThat(factory.dataSource).isSameAs(writer)
                val model = "com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model."
                assertThat(factory.persistenceUnitInfo!!.managedClassNames).containsExactlyInAnyOrder(
                    model + "BaseEntity", model + "Wishlist", model + "ProductSKU", model + "Product",
                    model + "Category", model + "SKUValue", model + "OptionValue", model + "OptionGroup",
                    model + "Order", model + "OrderItem",
                )
                assertThat(factory.jpaPropertyMap)
                    .containsEntry("hibernate.boot.allow_jdbc_metadata_access", false)
                    .containsEntry("hibernate.hbm2ddl.auto", "none")
                    .containsEntry("jakarta.persistence.schema-generation.database.action", "none")
                    .containsEntry("hibernate.default_schema", "pawbridge_store")
                    .containsEntry("hibernate.jdbc.time_zone", "UTC")
                    .containsEntry(
                        "hibernate.physical_naming_strategy",
                        "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy",
                    )
                val manager = context.getBean("storeDirectOrderJpaTransactionManager", JpaTransactionManager::class.java)
                assertThat(manager.entityManagerFactory).isSameAs(emf)
                assertThat(manager.dataSource).isSameAs(writer)
                assertThat(manager.defaultTimeout).isEqualTo(15)
                val orders = context.getBean(DirectOrders::class.java)
                val writes = context.getBean("storeDirectOrderWrites", TransactionTemplate::class.java)
                val reads = context.getBean("storeDirectOrderReads", TransactionTemplate::class.java)
                assertThat(ReflectionTestUtils.getField(orders, "writes")).isSameAs(writes)
                assertThat(ReflectionTestUtils.getField(orders, "reads")).isSameAs(reads)
                for (transaction in listOf(writes, reads)) {
                    assertThat(transaction.transactionManager).isSameAs(manager).isNotSameAs(readerManager)
                    assertThat(transaction.propagationBehavior).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED)
                    assertThat(transaction.isolationLevel).isEqualTo(TransactionDefinition.ISOLATION_DEFAULT)
                    assertThat(transaction.timeout).isEqualTo(TransactionDefinition.TIMEOUT_DEFAULT)
                }
                assertThat(writes.isReadOnly).isFalse()
                assertThat(reads.isReadOnly).isTrue()
                val shared = context.getBean("storeDirectOrderEntityManager", EntityManager::class.java)
                assertThat(shared.entityManagerFactory).isSameAs(emf)
                for (repository in listOf("writerOrders", "readerOrders")) {
                    val proxy: Any = ReflectionTestUtils.getField(orders, repository)!!
                    val target: Any = AopTestUtils.getUltimateTargetObject(proxy)
                    assertThat(ReflectionTestUtils.getField(target, "entityManager")).isSameAs(shared)
                }
                val actorJdbc = ReflectionTestUtils.getField(context.getBean(StoreOrderActors::class.java), "jdbc") as JdbcTemplate
                assertThat(actorJdbc.dataSource).isSameAs(writer)
                val search = context.getBean("storeDirectOrderSearchJdbc", NamedParameterJdbcTemplate::class.java)
                assertThat(search.jdbcTemplate.dataSource).isSameAs(writer)
                assertThat(search.jdbcTemplate.queryTimeout).isEqualTo(5)
                terms.set(context.getBean(KoreanSearchTerms::class.java))
                assertThat(terms.get().document("말티즈")).isNotBlank()
                assertThat(context)
                    .hasSingleBean(DirectOrderController::class.java)
                    .hasSingleBean(OrderReadController::class.java)
                    .hasSingleBean(StoreOrderSupportConfiguration::class.java)
                    .hasSingleBean(DirectOrderExceptionHandler::class.java)
            }
            if (mode == "store") {
                assertThat(context).doesNotHaveBean(AnimalCommandService::class.java)
            }
            confirmVerified(writer, reader, readerManager)
        }
        assertThat(units.get()).allSatisfy { factory -> assertThat(factory.isOpen).isFalse() }
        if (terms.get() != null) {
            assertThatThrownBy { terms.get().document("말티즈") }.isInstanceOf(IllegalStateException::class.java)
        }
    }

    @Test
    fun `사용자 액터 JDBC가 다른 소스를 쓸 때__주문을 켜면__DB 접근 없이 불일치 쓰기 DB를 거절한다`() {
        // given
        val writer = mockk<DataSource>()
        val other = mockk<DataSource>()

        excludeRecords {
            writer.hashCode()
            other.hashCode()
        }

        // when
        runner().withBean("writeDataSource", DataSource::class.java, Supplier { writer })
            .withBean("writeJdbc", JdbcTemplate::class.java, Supplier { JdbcTemplate(other) })
            .withBean(ObjectMapper::class.java, Supplier { ObjectMapper() })
            .withPropertyValues("pawbridge.store-direct-orders.enabled=true").run { context ->
                // then
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .hasRootCauseMessage("Store order actors must use the same writer DataSource")
                confirmVerified(writer, other)
            }
    }

    @Test
    fun `리더 입력만 있을 때__주문을 켜면__리더로 주문을 처리하지 않고 실패한다`() {
        // given
        val reader = mockk<DataSource>()

        excludeRecords {
            reader.hashCode()
        }

        // when
        runner().withBean("dataSource", DataSource::class.java, Supplier { reader })
            .withBean(ObjectMapper::class.java, Supplier { ObjectMapper() })
            .withPropertyValues("pawbridge.store-direct-orders.enabled=true").run { context ->
                // then
                assertThat(context).hasFailed()
                confirmVerified(reader)
            }
    }

    private fun runner(): ApplicationContextRunner {
        return ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration::class.java))
            .withUserConfiguration(
                StoreDirectOrderConfiguration::class.java,
                AnimalCommandConfiguration::class.java,
                StoreCatalogConfiguration::class.java,
            )
    }

    private fun writerInputs(writer: DataSource): ApplicationContextRunner {
        return runner().withBean("writeDataSource", DataSource::class.java, Supplier { writer })
            .withBean("writeJdbc", JdbcTemplate::class.java, Supplier { JdbcTemplate(writer) })
            .withBean(ObjectMapper::class.java, Supplier { ObjectMapper() })
    }
}
