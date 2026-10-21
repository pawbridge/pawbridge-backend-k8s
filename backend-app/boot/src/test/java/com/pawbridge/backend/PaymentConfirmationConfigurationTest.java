package com.pawbridge.backend;

import com.pawbridge.backend.boot.configuration.*;
import com.pawbridge.backend.payment.internal.confirmation.application.Payments;
import com.pawbridge.backend.payment.internal.confirmation.infrastructure.JpaPayments;
import com.pawbridge.backend.payment.internal.confirmation.infrastructure.jpa.repository.PaymentRepository;
import com.pawbridge.backend.payment.internal.confirmation.infrastructure.jpa.repository.OutboxRepository;
import com.pawbridge.backend.payment.internal.confirmation.infrastructure.toss.TossPaymentsClient;
import com.pawbridge.backend.payment.internal.confirmation.web.PaymentController;
import com.pawbridge.backend.payment.internal.confirmation.web.PaymentInputExceptionHandler;
import com.pawbridge.backend.store.api.StorePaymentOrders;
import com.pawbridge.backend.store.internal.payment.infrastructure.JdbcStorePaymentOrders;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.auditing.AuditingHandler;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class PaymentConfirmationConfigurationTest {
    private static final String SECRET = "synthetic-payment-config-secret-only";

    @ParameterizedTest @ValueSource(strings = {"absent", "false", "yes"})
    void given__a_missing_or_non_true_payment_flag__when__assembling_without_secret__then__create_no_payment_resources(String flag) {
        var runner = new WebApplicationContextRunner().withUserConfiguration(PaymentConfirmationConfiguration.class);
        if (!flag.equals("absent")) runner = runner.withPropertyValues("pawbridge.payments.enabled=" + flag);
        runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(Payments.class)
                .doesNotHaveBean(EntityManagerFactory.class).doesNotHaveBean(StorePaymentOrders.class)
                .doesNotHaveBean(HttpClient.class).doesNotHaveBean(PaymentController.class));
    }

    @Test
    void given__missing_secret_or_credential_bearing_endpoint__when__validating_configuration__then__reject_without_echoing_inputs() {
        assertThatThrownBy(() -> new PaymentConfirmationConfiguration("", "https://api.tosspayments.com/v1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("An explicit Toss secret key is required");
        assertThatThrownBy(() -> new PaymentConfirmationConfiguration(SECRET, "https://synthetic-private-user@provider.invalid/v1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("An explicit Toss endpoint with /v1 is required");
    }

    @Test
    void given__actual_main_and_explicit_synthetic_payment__when__assembling_and_closing__then__share_named_resources_without_startup_io_or_secret_logs(CapturedOutput output) {
        var values = F3TestInputs.values();
        values.put("F2_JDBC_URL", "jdbc:postgresql://127.0.0.1:1/pawbridge_payment_synthetic");
        var read = ReadDatabaseSettings.fromEnvironment(values::get);
        var write = PostWriteSettings.fromEnvironment(values::get, read);
        var factoryRef = new AtomicReference<EntityManagerFactory>();
        var clientRef = new AtomicReference<HttpClient>();
        var poolsRef = new AtomicReference<List<HikariDataSource>>();
        new WebApplicationContextRunner().withUserConfiguration(PawBridgeApplication.class, PaymentConfirmationConfiguration.class)
                .withInitializer(context -> {
                    context.getBeanFactory().registerSingleton("readDatabaseSettings", read);
                    context.getBeanFactory().registerSingleton("postWriteSettings", write);
                }).withPropertyValues("pawbridge.payments.enabled=true", "toss.secret-key=" + SECRET,
                        "toss.base-url=http://127.0.0.1:1/v1", "spring.sql.init.mode=never", "spring.jmx.enabled=false",
                        "pawbridge.chat.enabled=false", "pawbridge.jobs.cleanup-enabled=false",
                        "pawbridge.auth.google-enabled=false", "pawbridge.auth.sessions-enabled=false",
                        "pawbridge.user-maintenance.enabled=false", "pawbridge.community-maintenance.enabled=false",
                        "pawbridge.visits.enabled=false", "pawbridge.visits.collection-enabled=false",
                        "pawbridge.animal-query.projection-schedule-enabled=false", "apms.batch.schedule-enabled=false",
                        "apms-photo-archive.enabled=false", "tourapi.enabled=false", "tourapi.schedule-enabled=false",
                        "lost-gallery-feed.enabled=false", "pawbridge.favorite-delivery.enabled=false",
                        "pawbridge.shelter-observations.schedule-enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(Payments.class).hasSingleBean(PaymentController.class)
                            .hasSingleBean(PaymentInputExceptionHandler.class).hasSingleBean(AuditingHandler.class);
                    assertThat(context.getBeansOfType(javax.sql.DataSource.class)).containsOnlyKeys("dataSource", "writeDataSource");
                    assertThat(context.getBeansOfType(EntityManagerFactory.class)).containsOnlyKeys("paymentConfirmationEntityManagerFactory");
                    assertThat(context.getBeansOfType(PlatformTransactionManager.class)).containsOnlyKeys(
                            "transactionManager", "writeTransactionManager", "paymentConfirmationJpaTransactionManager");
                    var factory = context.getBean("paymentConfirmationEntityManagerFactory", EntityManagerFactory.class);
                    factoryRef.set(factory);
                    var definition = context.getBean("&paymentConfirmationEntityManagerFactory", LocalContainerEntityManagerFactoryBean.class);
                    assertThat(definition.getDataSource()).isSameAs(context.getBean("writeDataSource"));
                    assertThat(definition.getPersistenceUnitInfo().getManagedClassNames()).containsExactlyInAnyOrder(
                            "com.pawbridge.backend.payment.internal.confirmation.infrastructure.jpa.model.BaseEntity",
                            "com.pawbridge.backend.payment.internal.confirmation.infrastructure.jpa.model.Payment",
                            "com.pawbridge.backend.payment.internal.confirmation.infrastructure.jpa.model.Outbox");
                    assertThat(factory.getProperties()).containsEntry("hibernate.boot.allow_jdbc_metadata_access", false)
                            .containsEntry("hibernate.hbm2ddl.auto", "none").containsEntry("hibernate.default_schema", "pawbridge_payment")
                            .containsEntry("hibernate.jdbc.time_zone", "UTC");
                    var manager = context.getBean("paymentConfirmationJpaTransactionManager", JpaTransactionManager.class);
                    assertThat(manager.getEntityManagerFactory()).isSameAs(factory);
                    assertThat(manager.getDataSource()).isSameAs(context.getBean("writeDataSource"));
                    var shared = context.getBean("paymentConfirmationEntityManager", EntityManager.class);
                    assertThat(shared.getEntityManagerFactory()).isSameAs(factory);
                    for (Object repository : List.of(context.getBean(PaymentRepository.class), context.getBean(OutboxRepository.class))) {
                        Object target = AopTestUtils.getUltimateTargetObject(repository);
                        assertThat(ReflectionTestUtils.getField(target, "entityManager")).isSameAs(shared);
                    }
                    var payments = context.getBean(Payments.class); assertThat(payments).isInstanceOf(JpaPayments.class);
                    assertThat(ReflectionTestUtils.getField(payments, "tossPaymentsClient")).isSameAs(context.getBean(TossPaymentsClient.class));
                    assertThat(ReflectionTestUtils.getField(payments, "paymentRepository")).isSameAs(context.getBean(PaymentRepository.class));
                    assertThat(ReflectionTestUtils.getField(payments, "outboxRepository")).isSameAs(context.getBean(OutboxRepository.class));
                    var writes = context.getBean("paymentConfirmationWrites", TransactionTemplate.class);
                    assertThat(ReflectionTestUtils.getField(payments, "writes")).isSameAs(writes);
                    assertThat(writes.getTransactionManager()).isSameAs(manager);
                    assertThat(writes.isReadOnly()).isFalse();
                    var orders = context.getBean(StorePaymentOrders.class); assertThat(orders).isInstanceOf(JdbcStorePaymentOrders.class);
                    assertThat(ReflectionTestUtils.getField(payments, "storeServiceClient")).isSameAs(orders);
                    assertThat(ReflectionTestUtils.getField(orders, "jdbc")).isSameAs(context.getBean("readJdbc"));
                    assertThat(ReflectionTestUtils.getField(orders, "reads")).isSameAs(context.getBean("readOnlyTransaction"));
                    var reads = context.getBean("readOnlyTransaction", TransactionTemplate.class);
                    assertThat(reads.isReadOnly()).isTrue();
                    assertThat(reads.getTransactionManager()).isSameAs(context.getBean("transactionManager")).isNotSameAs(manager);
                    assertThat(((JdbcTemplate) ReflectionTestUtils.getField(orders, "jdbc")).getDataSource())
                            .isSameAs(context.getBean("dataSource")).isNotSameAs(context.getBean("writeDataSource"));
                    assertThat(ReflectionTestUtils.getField(context.getBean(PaymentController.class), "payments")).isSameAs(payments);
                    var client = context.getBean("paymentTossHttpClient", HttpClient.class); clientRef.set(client);
                    assertThat(client.connectTimeout()).contains(Duration.ofSeconds(10));
                    assertThat(client.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
                    var requestFactory = ReflectionTestUtils.getField(context.getBean("paymentTossRestClient", RestClient.class), "clientRequestFactory");
                    assertThat(ReflectionTestUtils.getField(requestFactory, "readTimeout")).isEqualTo(Duration.ofSeconds(60));
                    poolsRef.set(List.copyOf(context.getBeansOfType(HikariDataSource.class).values()));
                    poolsRef.get().forEach(pool -> {
                        assertThat(pool.getHikariPoolMXBean().getTotalConnections()).isZero();
                        assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
                    });
                    assertThat(output.getAll()).doesNotContain(SECRET);
                });
        assertThat(factoryRef.get().isOpen()).isFalse();
        assertThat(clientRef.get().isTerminated()).isTrue();
        assertThat(poolsRef.get()).allSatisfy(pool -> assertThat(pool.isClosed()).isTrue());
        assertThat(output.getAll()).doesNotContain(SECRET);
    }
}
