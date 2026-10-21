package com.pawbridge.backend.boot.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.payment.internal.confirmation.application.Payments;
import com.pawbridge.backend.payment.internal.confirmation.infrastructure.JpaPayments;
import com.pawbridge.backend.payment.internal.confirmation.infrastructure.jpa.repository.OutboxRepository;
import com.pawbridge.backend.payment.internal.confirmation.infrastructure.jpa.repository.PaymentRepository;
import com.pawbridge.backend.payment.internal.confirmation.infrastructure.toss.HttpTossPaymentsClient;
import com.pawbridge.backend.payment.internal.confirmation.infrastructure.toss.TossPaymentsClient;
import com.pawbridge.backend.payment.internal.confirmation.web.PaymentController;
import com.pawbridge.backend.payment.internal.confirmation.web.PaymentInputExceptionHandler;
import com.pawbridge.backend.store.api.StorePaymentOrders;
import com.pawbridge.backend.store.internal.payment.infrastructure.JdbcStorePaymentOrders;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

/** Default-off original confirmation assembly; code integration is not permission for real financial traffic. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pawbridge.payments", name = "enabled", havingValue = "true")
@Import(JpaAuditingConfiguration.class)
public class PaymentConfirmationConfiguration {
    private static final String MODEL = "com.pawbridge.backend.payment.internal.confirmation.infrastructure.jpa.model.";
    private final String secretKey;
    private final URI providerEndpoint;

    public PaymentConfirmationConfiguration(@Value("${toss.secret-key:}") String secretKey,
            @Value("${toss.base-url:https://api.tosspayments.com/v1}") String endpoint) {
        if (secretKey == null || secretKey.isBlank()) throw new IllegalArgumentException("An explicit Toss secret key is required");
        URI parsed;
        try { parsed = URI.create(endpoint); }
        catch (IllegalArgumentException | NullPointerException invalid) { throw new IllegalArgumentException("An explicit Toss endpoint with /v1 is required"); }
        if (!parsed.isAbsolute() || !("http".equals(parsed.getScheme()) || "https".equals(parsed.getScheme()))
                || parsed.getHost() == null || parsed.getUserInfo() != null || parsed.getQuery() != null
                || parsed.getFragment() != null || parsed.getPort() == 0 || parsed.getPort() > 65535 || !"/v1".equals(parsed.getPath()))
            throw new IllegalArgumentException("An explicit Toss endpoint with /v1 is required");
        this.secretKey = secretKey;
        this.providerEndpoint = parsed;
    }

    @Bean
    LocalContainerEntityManagerFactoryBean paymentConfirmationEntityManagerFactory(@Qualifier("writeDataSource") DataSource source) {
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(source);
        factory.setPersistenceUnitName("pawbridge-payment-confirmation-writer");
        factory.setManagedTypes(PersistenceManagedTypes.of(MODEL + "BaseEntity", MODEL + "Payment", MODEL + "Outbox"));
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect",
                "hibernate.boot.allow_jdbc_metadata_access", false, "hibernate.hbm2ddl.auto", "none",
                "jakarta.persistence.schema-generation.database.action", "none", "hibernate.default_schema", "pawbridge_payment",
                "hibernate.jdbc.time_zone", "UTC", "hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy"));
        return factory;
    }

    @Bean
    JpaTransactionManager paymentConfirmationJpaTransactionManager(
            @Qualifier("paymentConfirmationEntityManagerFactory") EntityManagerFactory factory,
            @Qualifier("writeDataSource") DataSource source) {
        var manager = new JpaTransactionManager(factory);
        manager.setDataSource(source);
        return manager;
    }

    @Bean EntityManager paymentConfirmationEntityManager(@Qualifier("paymentConfirmationEntityManagerFactory") EntityManagerFactory factory) {
        return SharedEntityManagerCreator.createSharedEntityManager(factory);
    }
    @Bean JpaRepositoryFactory paymentConfirmationRepositoryFactory(@Qualifier("paymentConfirmationEntityManager") EntityManager shared) {
        return new JpaRepositoryFactory(shared);
    }
    @Bean PaymentRepository paymentConfirmationPayments(@Qualifier("paymentConfirmationRepositoryFactory") JpaRepositoryFactory factory) {
        return factory.getRepository(PaymentRepository.class);
    }
    @Bean OutboxRepository paymentConfirmationOutbox(@Qualifier("paymentConfirmationRepositoryFactory") JpaRepositoryFactory factory) {
        return factory.getRepository(OutboxRepository.class);
    }
    @Bean TransactionTemplate paymentConfirmationWrites(@Qualifier("paymentConfirmationJpaTransactionManager") JpaTransactionManager manager) {
        return new TransactionTemplate(manager);
    }
    @Bean StorePaymentOrders storePaymentOrders(@Qualifier("readJdbc") JdbcTemplate reader,
            @Qualifier("readOnlyTransaction") TransactionTemplate reads) {
        return new JdbcStorePaymentOrders(reader, reads);
    }
    @Bean(destroyMethod = "close") HttpClient paymentTossHttpClient() {
        // feign-core13.5 Request.Options default: connect10sec/read60sec. No business retry is introduced.
        // Keep explicit Basic credentials off redirect targets; real financial activation remains a separate gate.
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    @Bean RestClient paymentTossRestClient(@Qualifier("paymentTossHttpClient") HttpClient client) {
        var requests = new JdkClientHttpRequestFactory(client);
        requests.setReadTimeout(Duration.ofSeconds(60));
        return RestClient.builder().baseUrl(providerEndpoint.toString()).requestFactory(requests).build();
    }
    @Bean TossPaymentsClient paymentTossClient(@Qualifier("paymentTossRestClient") RestClient client) {
        return new HttpTossPaymentsClient(client);
    }
    @Bean Payments payments(TossPaymentsClient client, PaymentRepository payments, OutboxRepository outbox,
            StorePaymentOrders orders, ObjectMapper mapper,
            @Qualifier("paymentConfirmationWrites") TransactionTemplate writes) {
        return new JpaPayments(client, payments, outbox, orders, mapper, secretKey, writes);
    }
    @Bean PaymentController paymentController(Payments payments) { return new PaymentController(payments); }
    @Bean PaymentInputExceptionHandler paymentInputExceptionHandler() { return new PaymentInputExceptionHandler(); }
}
