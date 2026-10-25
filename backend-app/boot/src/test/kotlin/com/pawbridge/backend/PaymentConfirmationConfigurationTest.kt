package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.PaymentConfirmationConfiguration
import com.pawbridge.backend.boot.configuration.PostWriteSettings
import com.pawbridge.backend.boot.configuration.ReadDatabaseSettings
import com.pawbridge.backend.boot.configuration.StorePaymentTransportConfiguration
import com.pawbridge.backend.payment.internal.confirmation.application.Payments
import com.pawbridge.backend.payment.internal.confirmation.infrastructure.JpaPayments
import com.pawbridge.backend.payment.internal.confirmation.infrastructure.jpa.repository.PaymentCompensationRecordRepository
import com.pawbridge.backend.payment.internal.confirmation.infrastructure.jpa.repository.PaymentRepository
import com.pawbridge.backend.payment.internal.confirmation.infrastructure.toss.TossPaymentsClient
import com.pawbridge.backend.payment.internal.confirmation.web.PaymentController
import com.pawbridge.backend.payment.internal.confirmation.web.PaymentInputExceptionHandler
import com.pawbridge.backend.store.api.StorePaymentEvents
import com.pawbridge.backend.store.api.StorePaymentOrders
import com.pawbridge.backend.store.internal.payment.infrastructure.JdbcStorePaymentOrders
import com.zaxxer.hikari.HikariDataSource
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.data.auditing.AuditingHandler
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.test.util.AopTestUtils
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier
import javax.sql.DataSource

@ExtendWith(OutputCaptureExtension::class)
class PaymentConfirmationConfigurationTest {
    @ParameterizedTest
    @ValueSource(strings = ["absent", "false", "yes"])
    fun given__a_missing_or_non_true_payment_flag__when__assembling_without_secret__then__create_no_payment_resources(
        flag: String,
    ) {
        var runner = WebApplicationContextRunner().withUserConfiguration(PaymentConfirmationConfiguration::class.java)
        if (flag != "absent") {
            runner = runner.withPropertyValues("pawbridge.payments.enabled=$flag")
        }
        runner.run { context ->
            assertThat(context).hasNotFailed()
                .doesNotHaveBean(Payments::class.java)
                .doesNotHaveBean(EntityManagerFactory::class.java)
                .doesNotHaveBean(StorePaymentOrders::class.java)
                .doesNotHaveBean(HttpClient::class.java)
                .doesNotHaveBean(PaymentController::class.java)
        }
    }

    @Test
    fun given__missing_secret_or_credential_bearing_endpoint__when__validating_configuration__then__reject_without_echoing_inputs() {
        assertThatThrownBy { PaymentConfirmationConfiguration("", "https://api.tosspayments.com/v1") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("An explicit Toss secret key is required")
        assertThatThrownBy {
            PaymentConfirmationConfiguration(SECRET, "https://synthetic-private-user@provider.invalid/v1")
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("An explicit Toss endpoint with /v1 is required")
    }

    @Test
    fun given__payments_without_the_store_payment_business__when__assembling__then__fail_at_startup_instead_of_running_without_order_updates() {
        val writer = mock(DataSource::class.java)
        WebApplicationContextRunner().withUserConfiguration(PaymentConfirmationConfiguration::class.java)
            .withPropertyValues("pawbridge.payments.enabled=true", "toss.secret-key=$SECRET")
            .withBean("writeDataSource", DataSource::class.java, Supplier { writer })
            .withBean("writeJdbc", JdbcTemplate::class.java, Supplier { JdbcTemplate(writer) })
            .withBean("readJdbc", JdbcTemplate::class.java, Supplier { JdbcTemplate(mock(DataSource::class.java)) })
            .withBean(
                "readOnlyTransaction",
                TransactionTemplate::class.java,
                Supplier { TransactionTemplate(mock(PlatformTransactionManager::class.java)) },
            )
            .run { context -> assertThat(context).hasFailed().failure.hasMessageContaining("StorePaymentEvents") }
    }

    @Test
    fun given__actual_main_and_explicit_synthetic_payment__when__assembling_and_closing__then__share_named_resources_without_startup_io_or_secret_logs(
        output: CapturedOutput,
    ) {
        val values = F3TestInputs.values()
        values["PAWBRIDGE_DB_URL"] = "jdbc:postgresql://127.0.0.1:1/pawbridge_payment_synthetic"
        val read = ReadDatabaseSettings.fromEnvironment { name -> values[name] }
        val write = PostWriteSettings.fromEnvironment { name -> values[name] }
        val factoryRef = AtomicReference<EntityManagerFactory>()
        val clientRef = AtomicReference<HttpClient>()
        val poolsRef = AtomicReference<List<HikariDataSource>>()
        WebApplicationContextRunner()
            .withUserConfiguration(PawBridgeApplication::class.java, PaymentConfirmationConfiguration::class.java)
            .withInitializer { context ->
                context.beanFactory.registerSingleton("readDatabaseSettings", read)
                context.beanFactory.registerSingleton("postWriteSettings", write)
            }
            .withBean(
                "storePaymentOwnedRedisFactory",
                StorePaymentTransportConfiguration.OwnedRedisFactory::class.java,
                Supplier {
                    StorePaymentTransportConfiguration.OwnedRedisFactory { settings ->
                        mock(LettuceConnectionFactory::class.java)
                    }
                },
            )
            .withPropertyValues(
                "pawbridge.payments.enabled=true",
                "pawbridge.store-payment-events.enabled=true",
                "spring.data.redis.host=127.0.0.1",
                "toss.secret-key=$SECRET",
                "toss.base-url=http://127.0.0.1:1/v1",
                "spring.sql.init.mode=never",
                "spring.jmx.enabled=false",
                "pawbridge.chat.enabled=false",
                "pawbridge.jobs.cleanup-enabled=false",
                "pawbridge.auth.google-enabled=false",
                "pawbridge.auth.sessions-enabled=false",
                "pawbridge.user-maintenance.enabled=false",
                "pawbridge.community-maintenance.enabled=false",
                "pawbridge.visits.enabled=false",
                "pawbridge.visits.collection-enabled=false",
                "pawbridge.animal-query.projection-schedule-enabled=false",
                "apms.batch.schedule-enabled=false",
                "apms-photo-archive.enabled=false",
                "tourapi.enabled=false",
                "tourapi.schedule-enabled=false",
                "lost-gallery-feed.enabled=false",
                "pawbridge.shelter-observations.schedule-enabled=false",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                    .hasSingleBean(Payments::class.java)
                    .hasSingleBean(PaymentController::class.java)
                    .hasSingleBean(PaymentInputExceptionHandler::class.java)
                    .hasSingleBean(AuditingHandler::class.java)
                    .hasSingleBean(StorePaymentEvents::class.java)
                assertThat(context.getBeansOfType(DataSource::class.java)).containsOnlyKeys("dataSource")
                // One Store writer unit and manager; no payment-only EMF/TM that could nest on the one-connection writer pool.
                assertThat(context.getBeansOfType(EntityManagerFactory::class.java))
                    .containsOnlyKeys("storeDirectOrderEntityManagerFactory")
                assertThat(context.getBeansOfType(PlatformTransactionManager::class.java)).containsOnlyKeys(
                    "transactionManager",
                    "writeTransactionManager",
                    "storeDirectOrderJpaTransactionManager",
                )
                val factory = context.getBean("storeDirectOrderEntityManagerFactory", EntityManagerFactory::class.java)
                factoryRef.set(factory)
                val definition = context.getBean(
                    "&storeDirectOrderEntityManagerFactory",
                    LocalContainerEntityManagerFactoryBean::class.java,
                )
                assertThat(definition.dataSource).isSameAs(context.getBean("writeDataSource"))
                assertThat(definition.persistenceUnitInfo!!.managedClassNames)
                    .contains(
                        "com.pawbridge.backend.payment.internal.confirmation.infrastructure.jpa.model.BaseEntity",
                        "com.pawbridge.backend.payment.internal.confirmation.infrastructure.jpa.model.Payment",
                        "com.pawbridge.backend.payment.internal.confirmation.infrastructure.jpa.model.PaymentCompensationRecord",
                        "com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.Order",
                    )
                    .noneMatch { name -> name.endsWith(".Outbox") }
                assertThat(factory.properties)
                    .containsEntry("hibernate.boot.allow_jdbc_metadata_access", false)
                    .containsEntry("hibernate.hbm2ddl.auto", "none")
                    .containsEntry("hibernate.jdbc.time_zone", "UTC")
                val manager = context.getBean("storeDirectOrderJpaTransactionManager", JpaTransactionManager::class.java)
                assertThat(manager.entityManagerFactory).isSameAs(factory)
                assertThat(manager.dataSource).isSameAs(context.getBean("writeDataSource"))
                val shared = context.getBean("storeDirectOrderEntityManager", EntityManager::class.java)
                val repositories = listOf<Any>(
                    context.getBean(PaymentRepository::class.java),
                    context.getBean(PaymentCompensationRecordRepository::class.java),
                )
                for (repository in repositories) {
                    val target = AopTestUtils.getUltimateTargetObject<Any>(repository)
                    assertThat(ReflectionTestUtils.getField(target, "entityManager")).isSameAs(shared)
                }
                val payments = context.getBean(Payments::class.java)
                assertThat(payments).isInstanceOf(JpaPayments::class.java)
                assertThat(ReflectionTestUtils.getField(payments, "tossPaymentsClient"))
                    .isSameAs(context.getBean(TossPaymentsClient::class.java))
                assertThat(ReflectionTestUtils.getField(payments, "paymentRepository"))
                    .isSameAs(context.getBean(PaymentRepository::class.java))
                assertThat(ReflectionTestUtils.getField(payments, "compensations"))
                    .isSameAs(context.getBean(PaymentCompensationRecordRepository::class.java))
                assertThat(ReflectionTestUtils.getField(payments, "storePayments"))
                    .isSameAs(context.getBean(StorePaymentEvents::class.java))
                val writes = context.getBean("storeDirectOrderWrites", TransactionTemplate::class.java)
                assertThat(ReflectionTestUtils.getField(payments, "writes")).isSameAs(writes)
                assertThat(writes.transactionManager).isSameAs(manager)
                assertThat(writes.isReadOnly).isFalse()
                val paymentReads = context.getBean("storeDirectOrderReads", TransactionTemplate::class.java)
                assertThat(ReflectionTestUtils.getField(payments, "reads")).isSameAs(paymentReads)
                assertThat(paymentReads.transactionManager).isSameAs(manager)
                assertThat(paymentReads.isReadOnly).isTrue()
                val orders = context.getBean(StorePaymentOrders::class.java)
                assertThat(orders).isInstanceOf(JdbcStorePaymentOrders::class.java)
                assertThat(ReflectionTestUtils.getField(payments, "storeServiceClient")).isSameAs(orders)
                assertThat(ReflectionTestUtils.getField(orders, "jdbc")).isSameAs(context.getBean("readJdbc"))
                assertThat(ReflectionTestUtils.getField(orders, "reads")).isSameAs(context.getBean("readOnlyTransaction"))
                val reads = context.getBean("readOnlyTransaction", TransactionTemplate::class.java)
                assertThat(reads.isReadOnly).isTrue()
                assertThat(reads.transactionManager).isSameAs(context.getBean("transactionManager")).isNotSameAs(manager)
                assertThat((ReflectionTestUtils.getField(orders, "jdbc") as JdbcTemplate).dataSource)
                    .isSameAs(context.getBean("dataSource"))
                    .isSameAs(context.getBean("writeDataSource"))
                assertThat(ReflectionTestUtils.getField(context.getBean(PaymentController::class.java), "payments"))
                    .isSameAs(payments)
                val client = context.getBean("paymentTossHttpClient", HttpClient::class.java)
                clientRef.set(client)
                assertThat(client.connectTimeout()).contains(Duration.ofSeconds(10))
                assertThat(client.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER)
                val requestFactory = ReflectionTestUtils.getField(
                    context.getBean("paymentTossRestClient", RestClient::class.java),
                    "clientRequestFactory",
                )
                assertThat(ReflectionTestUtils.getField(requestFactory!!, "readTimeout")).isEqualTo(Duration.ofSeconds(60))
                poolsRef.set(context.getBeansOfType(HikariDataSource::class.java).values.toList())
                poolsRef.get().forEach { pool ->
                    assertThat(pool.hikariPoolMXBean.totalConnections).isZero()
                    assertThat(pool.hikariPoolMXBean.activeConnections).isZero()
                }
                assertThat(output.all).doesNotContain(SECRET)
            }
        assertThat(factoryRef.get().isOpen).isFalse()
        assertThat(clientRef.get().isTerminated).isTrue()
        assertThat(poolsRef.get()).allSatisfy { pool -> assertThat(pool.isClosed).isTrue() }
        assertThat(output.all).doesNotContain(SECRET)
    }

    companion object {
        private const val SECRET = "synthetic-payment-config-secret-only"
    }
}
