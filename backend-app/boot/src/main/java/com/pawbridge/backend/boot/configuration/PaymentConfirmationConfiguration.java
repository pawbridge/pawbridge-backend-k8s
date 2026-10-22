package com.pawbridge.backend.boot.configuration;

import com.pawbridge.backend.payment.internal.confirmation.application.Payments;
import com.pawbridge.backend.payment.internal.confirmation.infrastructure.JpaPayments;
import com.pawbridge.backend.payment.internal.confirmation.infrastructure.jpa.repository.PaymentCompensationRecordRepository;
import com.pawbridge.backend.payment.internal.confirmation.infrastructure.jpa.repository.PaymentRepository;
import com.pawbridge.backend.payment.internal.confirmation.infrastructure.toss.HttpTossPaymentsClient;
import com.pawbridge.backend.payment.internal.confirmation.infrastructure.toss.TossPaymentsClient;
import com.pawbridge.backend.payment.internal.confirmation.web.PaymentController;
import com.pawbridge.backend.payment.internal.confirmation.web.PaymentInputExceptionHandler;
import com.pawbridge.backend.store.api.StorePaymentEvents;
import com.pawbridge.backend.store.api.StorePaymentOrders;
import com.pawbridge.backend.store.internal.payment.infrastructure.JdbcStorePaymentOrders;
import jakarta.persistence.EntityManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

/**
 * Default-off confirmation assembly; code integration is not permission for real financial traffic.
 * Payment rows share the Store writer persistence unit and transaction manager (no second JPA unit or nested manager).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pawbridge.payments", name = "enabled", havingValue = "true")
@Import({JpaAuditingConfiguration.class, StoreJpaWriterConfiguration.class})
public class PaymentConfirmationConfiguration {
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

    @Bean PaymentRepository paymentConfirmationPayments(@Qualifier("storeDirectOrderEntityManager") EntityManager shared) {
        return new JpaRepositoryFactory(shared).getRepository(PaymentRepository.class);
    }
    @Bean PaymentCompensationRecordRepository paymentConfirmationCompensations(@Qualifier("storeDirectOrderEntityManager") EntityManager shared) {
        return new JpaRepositoryFactory(shared).getRepository(PaymentCompensationRecordRepository.class);
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
    /** Requires the Store payment business (pawbridge.store-payment-events.enabled); startup fails without it. */
    @Bean Payments payments(TossPaymentsClient client, PaymentRepository payments,
            PaymentCompensationRecordRepository compensations, StorePaymentOrders orders, StorePaymentEvents storePayments,
            @Qualifier("storeDirectOrderWrites") TransactionTemplate writes,
            @Qualifier("storeDirectOrderReads") TransactionTemplate reads) {
        return new JpaPayments(client, payments, compensations, orders, storePayments, secretKey, writes, reads, Clock.systemDefaultZone());
    }
    @Bean PaymentController paymentController(Payments payments) { return new PaymentController(payments); }
    @Bean PaymentInputExceptionHandler paymentInputExceptionHandler() { return new PaymentInputExceptionHandler(); }
}
