package com.pawbridge.backend.boot.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.store.api.StoreMemberReads
import com.pawbridge.backend.store.api.StoreOrderActors
import com.pawbridge.backend.store.internal.catalog.application.StoreCatalog
import com.pawbridge.backend.store.internal.catalog.infrastructure.KoreanSearchTerms
import com.pawbridge.backend.store.internal.directorder.application.CartOrders
import com.pawbridge.backend.store.internal.directorder.application.DirectOrders
import com.pawbridge.backend.store.internal.directorder.web.CartOrderController
import com.pawbridge.backend.store.internal.directorder.web.DirectOrderController
import com.pawbridge.backend.store.internal.directorder.web.DirectOrderExceptionHandler
import com.pawbridge.backend.store.internal.directorder.web.OrderReadController
import io.mockk.confirmVerified
import io.mockk.excludeRecords
import io.mockk.mockk
import jakarta.persistence.EntityManager
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
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.util.AopTestUtils
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.method.HandlerMethod
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier
import javax.sql.DataSource

class StoreCartConfigurationTest {
    @ParameterizedTest
    @ValueSource(strings = ["absent", "false", "yes", "on", "1", "unknown"])
    fun `쓰기 기능이 둘 다 명시적 true가 아닐 때__DB 입력 없이 조립하면__쓰기·카트 빈을 등록하지 않는다`(
        flag: String,
    ) {
        // given
        var runner = runner()
        if (flag != "absent") {
            runner = runner.withPropertyValues(
                "pawbridge.store-carts.enabled=$flag",
                "pawbridge.store-direct-orders.enabled=$flag",
            )
        }

        // when
        runner.run { context ->
            // then
            assertThat(context).hasNotFailed()
                .doesNotHaveBean(StoreJpaWriterConfiguration::class.java)
                .doesNotHaveBean(EntityManagerFactory::class.java)
                .doesNotHaveBean(StoreOrderActors::class.java)
                .doesNotHaveBean(AuditingHandler::class.java)
                .doesNotHaveBean(DirectOrders::class.java)
                .doesNotHaveBean(StoreOrderSupportConfiguration::class.java)
                .doesNotHaveBean(OrderReadController::class.java)
                .doesNotHaveBean(DirectOrderController::class.java)
                .doesNotHaveBean(DirectOrderExceptionHandler::class.java)
                .doesNotHaveBean(CartOrders::class.java)
                .doesNotHaveBean(CartOrderController::class.java)
                .doesNotHaveBean(KoreanSearchTerms::class.java)
                .doesNotHaveBean("storeCarts")
                .doesNotHaveBean("cartController")
                .doesNotHaveBean("cartExceptionHandler")
                .doesNotHaveBean(StoreMemberReads::class.java)
                .doesNotHaveBean(StoreCatalog::class.java)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["cart", "both", "direct-with-cart-on"])
    fun `선택된 스토어 쓰기 기능이 주어졌을 때__조립하면__다른 API를 켜지 않고 쓰기 DB와 감사자 하나를 공유한다`(
        mode: String,
    ) {
        val carts = mode != "direct-with-cart-on"
        val orders = mode != "cart"
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
                "pawbridge.store-carts.enabled=" + (if (carts) "TrUe" else "on"),
                "pawbridge.store-direct-orders.enabled=$orders",
            )
            .run { context ->
                // then
                assertThat(context).hasNotFailed()
                    .hasSingleBean(StoreJpaWriterConfiguration::class.java)
                    .hasSingleBean(EntityManagerFactory::class.java)
                    .hasSingleBean(JpaTransactionManager::class.java)
                    .hasSingleBean(StoreOrderSupportConfiguration::class.java)
                    .hasSingleBean(OrderReadController::class.java)
                    .hasSingleBean(DirectOrderExceptionHandler::class.java)
                    .hasSingleBean(JpaAuditingConfiguration::class.java)
                    .hasSingleBean(AuditingHandler::class.java)
                    .hasSingleBean(StoreOrderActors::class.java)
                    .doesNotHaveBean(StoreMemberReads::class.java)
                    .doesNotHaveBean(StoreCatalog::class.java)
                    .doesNotHaveBean("storeMemberEntityManagerFactory")
                unit.set(context.getBean("storeDirectOrderEntityManagerFactory", EntityManagerFactory::class.java))
                val factory = context.getBean(
                    "&storeDirectOrderEntityManagerFactory",
                    LocalContainerEntityManagerFactoryBean::class.java,
                )
                assertThat(factory.dataSource).isSameAs(writer).isNotSameAs(reader)
                val model = "com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model."
                val managed = ArrayList(
                    listOf(
                        model + "BaseEntity", model + "Wishlist", model + "ProductSKU",
                        model + "Product", model + "Category", model + "SKUValue", model + "OptionValue",
                        model + "OptionGroup", model + "Order", model + "OrderItem",
                    ),
                )
                if (carts) {
                    managed.addAll(
                        listOf(
                            "com.pawbridge.backend.store.internal.cart.infrastructure.jpa.model.Cart",
                            "com.pawbridge.backend.store.internal.cart.infrastructure.jpa.model.CartItem",
                        ),
                    )
                }
                assertThat(factory.persistenceUnitInfo!!.managedClassNames).containsExactlyInAnyOrderElementsOf(managed)
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
                assertThat(manager.dataSource).isSameAs(writer)
                assertThat(manager.entityManagerFactory).isSameAs(unit.get())
                assertThat(manager.defaultTimeout).isEqualTo(15)
                val writes = context.getBean("storeDirectOrderWrites", TransactionTemplate::class.java)
                val reads = context.getBean("storeDirectOrderReads", TransactionTemplate::class.java)
                for (transaction in listOf(writes, reads)) {
                    assertThat(transaction.transactionManager).isSameAs(manager).isNotSameAs(readerManager)
                    assertThat(transaction.propagationBehavior).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED)
                    assertThat(transaction.isolationLevel).isEqualTo(TransactionDefinition.ISOLATION_DEFAULT)
                    assertThat(transaction.timeout).isEqualTo(TransactionDefinition.TIMEOUT_DEFAULT)
                }
                assertThat(writes.isReadOnly).isFalse()
                assertThat(reads.isReadOnly).isTrue()
                val shared = context.getBean("storeDirectOrderEntityManager", EntityManager::class.java)
                assertThat(shared.entityManagerFactory).isSameAs(unit.get())
                val actorJdbc = ReflectionTestUtils.getField(context.getBean(StoreOrderActors::class.java), "jdbc") as JdbcTemplate
                assertThat(actorJdbc.dataSource).isSameAs(writer)
                assertThat(context.containsBean("storeCarts")).isEqualTo(carts)
                if (carts) {
                    val cartService: Any = context.getBean("storeCarts")
                    assertThat(ReflectionTestUtils.getField(cartService, "em")).isSameAs(shared)
                    assertThat(ReflectionTestUtils.getField(cartService, "writes")).isSameAs(writes)
                    assertThat(ReflectionTestUtils.getField(cartService, "reads")).isSameAs(reads)
                    assertThat(ReflectionTestUtils.getField(cartService, "actors"))
                        .isSameAs(context.getBean(StoreOrderActors::class.java))
                    val repository: Any = ReflectionTestUtils.getField(cartService, "carts")!!
                    val target: Any = AopTestUtils.getUltimateTargetObject(repository)
                    assertThat(ReflectionTestUtils.getField(target, "entityManager")).isSameAs(shared)
                    val checkout: Any = context.getBean(CartOrders::class.java)
                    assertThat(ReflectionTestUtils.getField(checkout, "writes")).isSameAs(writes)
                    assertThat(ReflectionTestUtils.getField(checkout, "actors"))
                        .isSameAs(context.getBean(StoreOrderActors::class.java))
                    for (name in listOf("carts", "orders", "skus")) {
                        val checkoutRepository: Any = ReflectionTestUtils.getField(checkout, name)!!
                        val checkoutTarget: Any = AopTestUtils.getUltimateTargetObject(checkoutRepository)
                        assertThat(ReflectionTestUtils.getField(checkoutTarget, "entityManager")).isSameAs(shared)
                    }
                }
                assertThat(context.containsBean("cartController")).isEqualTo(carts)
                assertThat(context.containsBean("cartExceptionHandler")).isEqualTo(carts)
                assertThat(context.getBeansOfType(DirectOrders::class.java)).hasSize(1)
                assertThat(context.getBeansOfType(CartOrders::class.java)).hasSize(if (carts) 1 else 0)
                assertThat(context.getBeansOfType(CartOrderController::class.java)).hasSize(if (carts) 1 else 0)
                assertThat(context.getBeansOfType(DirectOrderController::class.java)).hasSize(if (orders) 1 else 0)
                assertThat(context.getBeansOfType(KoreanSearchTerms::class.java)).hasSize(1)
                assertThat(context.containsBean("storeDirectOrderSearchJdbc")).isTrue()
                confirmVerified(writer, reader, readerManager)
            }
        assertThat(unit.get().isOpen).isFalse()
    }

    @ParameterizedTest
    @ValueSource(strings = ["reader-only", "mismatched-jdbc"])
    fun `일관된 쓰기 입력이 없을 때__카트를 켜면__리더 접근으로 대체하지 않고 거절한다`(
        mode: String,
    ) {
        // given
        val writer = mockk<DataSource>()
        val other = mockk<DataSource>()

        excludeRecords {
            writer.hashCode()
            other.hashCode()
        }

        var runner = runner().withBean(ObjectMapper::class.java, Supplier { ObjectMapper() })
            .withPropertyValues("pawbridge.store-carts.enabled=true")
        runner = if (mode == "reader-only") {
            runner.withBean("dataSource", DataSource::class.java, Supplier { other })
        } else {
            runner.withBean("writeDataSource", DataSource::class.java, Supplier { writer })
                .withBean("writeJdbc", JdbcTemplate::class.java, Supplier { JdbcTemplate(other) })
        }

        // when
        runner.run { context ->
            // then
            assertThat(context).hasFailed()
            if (mode == "mismatched-jdbc") {
                assertThat(context.startupFailure)
                    .hasRootCauseMessage("Store order actors must use the same writer DataSource")
            }
            confirmVerified(writer, other)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["cart", "direct", "both"])
    fun `주문 기능이 켜졌을 때__HTTP를 매핑하면__공용 읽기 컨트롤러 하나와 소유한 POST만 노출한다`(
        mode: String,
    ) {
        val carts = mode != "direct"
        val direct = mode != "cart"
        // given
        val writer = mockk<DataSource>()

        excludeRecords {
            writer.hashCode()
        }

        // when
        runner().withBean("writeDataSource", DataSource::class.java, Supplier { writer })
            .withBean("writeJdbc", JdbcTemplate::class.java, Supplier { JdbcTemplate(writer) })
            .withBean(ObjectMapper::class.java, Supplier { ObjectMapper() })
            .withPropertyValues(
                "pawbridge.store-carts.enabled=$carts",
                "pawbridge.store-direct-orders.enabled=$direct",
            )
            .run { context ->
                // then
                assertThat(context).hasNotFailed().hasSingleBean(OrderReadController::class.java)
                val controllers = context.getBeansWithAnnotation(RestController::class.java).values.toTypedArray()
                val advice = context.getBeansOfType(DirectOrderExceptionHandler::class.java).values.toTypedArray()
                SecurityContextHolder.clearContext()
                try {
                    // No SecurityFilterChain is installed: this protects bean-to-handler exposure only.
                    // Missing principals/invalid bodies stop before any business SQL.
                    val mvc = MockMvcBuilders.standaloneSetup(*controllers)
                        .setControllerAdvice(*advice)
                        .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
                        .build()
                    val read = mvc.perform(get("/api/v1/orders/7")).andReturn()
                    assertThat(read.response.status).isEqualTo(401)
                    assertThat((read.handler as HandlerMethod).beanType).isEqualTo(OrderReadController::class.java)
                    val directPost = mvc.perform(
                        post("/api/v1/orders/direct").contentType("application/json").content("{}"),
                    ).andReturn()
                    if (direct) {
                        assertThat((directPost.handler as HandlerMethod).beanType)
                            .isEqualTo(DirectOrderController::class.java)
                        assertThat(directPost.response.status).isIn(400, 401)
                    } else {
                        // The shared GET /orders/{orderId} matches this path, but owns no POST.
                        assertThat(directPost.response.status).isEqualTo(405)
                        assertThat(directPost.handler).isNull()
                    }
                    val cartPost = mvc.perform(
                        post("/api/v1/orders").contentType("application/json").content("{}"),
                    ).andReturn()
                    if (carts) {
                        assertThat((cartPost.handler as HandlerMethod).beanType)
                            .isEqualTo(CartOrderController::class.java)
                        assertThat(cartPost.response.status).isEqualTo(401)
                    } else {
                        // Collection GET exists; standalone MVC rejects its unsupported POST as 405.
                        // The SecurityFilterChain separately denies this unowned POST before MVC.
                        assertThat(cartPost.response.status).isEqualTo(405)
                        assertThat(cartPost.handler).isNull()
                    }
                } finally {
                    SecurityContextHolder.clearContext()
                }
                confirmVerified(writer)
            }
    }

    private fun runner(): ApplicationContextRunner {
        return ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration::class.java))
            .withUserConfiguration(
                StoreDirectOrderConfiguration::class.java,
                StoreCartConfiguration::class.java,
                StoreCatalogConfiguration::class.java,
            )
    }
}
