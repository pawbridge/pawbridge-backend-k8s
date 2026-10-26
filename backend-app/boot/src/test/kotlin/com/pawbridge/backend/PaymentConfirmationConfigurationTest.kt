package com.pawbridge.backend

import com.pawbridge.backend.boot.configuration.PaymentConfirmationConfiguration
import com.pawbridge.backend.boot.configuration.PostWriteSettings
import com.pawbridge.backend.boot.configuration.ReadDatabaseSettings
import com.pawbridge.backend.boot.configuration.StorePaymentTransportConfiguration
import com.pawbridge.backend.payment.internal.confirmation.application.Payments
import com.pawbridge.backend.payment.internal.confirmation.infrastructure.JpaPayments
import com.pawbridge.backend.payment.internal.confirmation.web.PaymentController
import com.pawbridge.backend.payment.internal.confirmation.web.PaymentInputExceptionHandler
import com.pawbridge.backend.store.api.StorePaymentEvents
import com.pawbridge.backend.store.api.StorePaymentOrders
import com.pawbridge.backend.store.internal.payment.infrastructure.JdbcStorePaymentOrders
import com.zaxxer.hikari.HikariDataSource
import io.mockk.justRun
import io.mockk.mockk
import jakarta.persistence.EntityManagerFactory
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.data.auditing.AuditingHandler
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.net.http.HttpClient
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier
import javax.sql.DataSource

@ExtendWith(OutputCaptureExtension::class)
class PaymentConfirmationConfigurationTest {
    @ParameterizedTest
    @ValueSource(strings = ["absent", "false", "yes"])
    fun `결제 플래그가 없거나 true가 아닐 때__비밀 없이 조립하면__결제 리소스를 만들지 않는다`(
        flag: String,
    ) {
        // given
        var runner = WebApplicationContextRunner().withUserConfiguration(PaymentConfirmationConfiguration::class.java)
        if (flag != "absent") {
            runner = runner.withPropertyValues("pawbridge.payments.enabled=$flag")
        }

        // when
        runner.run { context ->
            // then
            assertThat(context).hasNotFailed()
                .doesNotHaveBean(Payments::class.java)
                .doesNotHaveBean(EntityManagerFactory::class.java)
                .doesNotHaveBean(StorePaymentOrders::class.java)
                .doesNotHaveBean(HttpClient::class.java)
                .doesNotHaveBean(PaymentController::class.java)
        }
    }

    @Test
    fun `비밀이 없거나 자격 증명이 든 엔드포인트가 주어졌을 때__설정을 검증하면__입력을 되풀이하지 않고 거부한다`() {
        // given
        val missingSecretEndpoint = "https://api.tosspayments.com/v1"
        val credentialEndpoint = "https://synthetic-private-user@provider.invalid/v1"

        // when
        val missingSecret = assertThatThrownBy { PaymentConfirmationConfiguration("", missingSecretEndpoint) }
        val credentialBearing = assertThatThrownBy { PaymentConfirmationConfiguration(SECRET, credentialEndpoint) }

        // then
        missingSecret
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("An explicit Toss secret key is required")
        credentialBearing
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("An explicit Toss endpoint with /v1 is required")
    }

    @Test
    fun `스토어 결제 업무 없는 결제가 주어졌을 때__조립하면__주문 갱신 없이 실행하지 않고 시작 시 실패한다`() {
        // given
        val writer = mockk<DataSource>()
        val reader = mockk<DataSource>()
        val transactionManager = mockk<PlatformTransactionManager>()

        // when
        WebApplicationContextRunner().withUserConfiguration(PaymentConfirmationConfiguration::class.java)
            .withPropertyValues("pawbridge.payments.enabled=true", "toss.secret-key=$SECRET")
            .withBean("writeDataSource", DataSource::class.java, Supplier { writer })
            .withBean("writeJdbc", JdbcTemplate::class.java, Supplier { JdbcTemplate(writer) })
            .withBean("readJdbc", JdbcTemplate::class.java, Supplier { JdbcTemplate(reader) })
            .withBean(
                "readOnlyTransaction",
                TransactionTemplate::class.java,
                Supplier { TransactionTemplate(transactionManager) },
            )
            .run { context ->
                // then
                assertThat(context).hasFailed().failure.hasMessageContaining("StorePaymentEvents")
            }
    }

    @Test
    fun `실제 main과 명시적 가짜 결제가 주어졌을 때__조립하고 닫으면__시작 I-O와 비밀 로그 없이 쓰기 단위 하나만 쓴다`(
        output: CapturedOutput,
    ) {
        // given
        val values = F3TestInputs.values()
        values["PAWBRIDGE_DB_URL"] = "jdbc:postgresql://127.0.0.1:1/pawbridge_payment_synthetic"
        val read = ReadDatabaseSettings.fromEnvironment { name -> values[name] }
        val write = PostWriteSettings.fromEnvironment { name -> values[name] }
        val factoryRef = AtomicReference<EntityManagerFactory>()
        val clientRef = AtomicReference<HttpClient>()
        val poolsRef = AtomicReference<List<HikariDataSource>>()
        val redisFactory = mockk<LettuceConnectionFactory>()

        justRun { redisFactory.afterPropertiesSet() }
        justRun { redisFactory.destroy() }

        // when
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
                        redisFactory
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
                // then
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
                val writes = context.getBean("storeDirectOrderWrites", TransactionTemplate::class.java)
                assertThat(writes.isReadOnly).isFalse()
                assertThat(context.getBean("storeDirectOrderReads", TransactionTemplate::class.java).isReadOnly).isTrue()
                assertThat(context.getBean("readOnlyTransaction", TransactionTemplate::class.java).isReadOnly).isTrue()
                assertThat(context.getBean(Payments::class.java)).isInstanceOf(JpaPayments::class.java)
                assertThat(context.getBean(StorePaymentOrders::class.java)).isInstanceOf(JdbcStorePaymentOrders::class.java)
                val client = context.getBean("paymentTossHttpClient", HttpClient::class.java)
                clientRef.set(client)
                assertThat(client.connectTimeout()).contains(Duration.ofSeconds(10))
                assertThat(client.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER)
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
