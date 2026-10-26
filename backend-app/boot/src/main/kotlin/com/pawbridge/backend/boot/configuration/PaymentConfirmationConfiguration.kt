package com.pawbridge.backend.boot.configuration

import com.pawbridge.backend.boot.javaIsBlank
import com.pawbridge.backend.payment.internal.confirmation.application.Payments
import com.pawbridge.backend.payment.internal.confirmation.infrastructure.JpaPayments
import com.pawbridge.backend.payment.internal.confirmation.infrastructure.jpa.repository.PaymentCompensationRecordRepository
import com.pawbridge.backend.payment.internal.confirmation.infrastructure.jpa.repository.PaymentRepository
import com.pawbridge.backend.payment.internal.confirmation.infrastructure.toss.HttpTossPaymentsClient
import com.pawbridge.backend.payment.internal.confirmation.infrastructure.toss.TossPaymentsClient
import com.pawbridge.backend.payment.internal.confirmation.web.PaymentController
import com.pawbridge.backend.payment.internal.confirmation.web.PaymentInputExceptionHandler
import com.pawbridge.backend.store.api.StorePaymentEvents
import com.pawbridge.backend.store.api.StorePaymentOrders
import com.pawbridge.backend.store.internal.payment.infrastructure.JdbcStorePaymentOrders
import jakarta.persistence.EntityManager
import java.net.URI
import java.net.http.HttpClient
import java.time.Clock
import java.time.Duration
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.client.RestClient

/**
 * Default-off confirmation assembly; code integration is not permission for real financial traffic.
 * Payment rows share the Store writer persistence unit and transaction manager (no second JPA unit or nested manager).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pawbridge.payments", name = ["enabled"], havingValue = "true")
@Import(JpaAuditingConfiguration::class, StoreJpaWriterConfiguration::class)
class PaymentConfirmationConfiguration(
    @Value("\${toss.secret-key:}") secretKey: String?,
    @Value("\${toss.base-url:https://api.tosspayments.com/v1}") endpoint: String?,
) {
    private val secretKey: String = requireSecretKey(secretKey)
    private val providerEndpoint: URI = parseProviderEndpoint(endpoint)

    @Bean
    fun paymentConfirmationPayments(
        @Qualifier("storeDirectOrderEntityManager") shared: EntityManager,
    ): PaymentRepository {
        return JpaRepositoryFactory(shared).getRepository(PaymentRepository::class.java)
    }

    @Bean
    fun paymentConfirmationCompensations(
        @Qualifier("storeDirectOrderEntityManager") shared: EntityManager,
    ): PaymentCompensationRecordRepository {
        return JpaRepositoryFactory(shared).getRepository(PaymentCompensationRecordRepository::class.java)
    }

    @Bean
    fun storePaymentOrders(
        @Qualifier("readJdbc") reader: JdbcTemplate,
        @Qualifier("readOnlyTransaction") reads: TransactionTemplate,
    ): StorePaymentOrders {
        return JdbcStorePaymentOrders(reader, reads)
    }

    @Bean(destroyMethod = "close")
    fun paymentTossHttpClient(): HttpClient {
        // feign-core 13.5 Request.Options default: connect 10 sec / read 60 sec. No business retry is introduced.
        // Keep explicit Basic credentials off redirect targets; real financial activation remains a separate gate.
        return HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
    }

    @Bean
    fun paymentTossRestClient(@Qualifier("paymentTossHttpClient") client: HttpClient): RestClient {
        val requests = JdkClientHttpRequestFactory(client)
        requests.setReadTimeout(Duration.ofSeconds(60))
        return RestClient.builder()
            .baseUrl(providerEndpoint.toString())
            .requestFactory(requests)
            .build()
    }

    @Bean
    fun paymentTossClient(@Qualifier("paymentTossRestClient") client: RestClient): TossPaymentsClient {
        return HttpTossPaymentsClient(client)
    }

    /** Requires the Store payment business (pawbridge.store-payment-events.enabled); startup fails without it. */
    @Bean
    fun payments(
        client: TossPaymentsClient,
        payments: PaymentRepository,
        compensations: PaymentCompensationRecordRepository,
        orders: StorePaymentOrders,
        storePayments: StorePaymentEvents,
        @Qualifier("storeDirectOrderWrites") writes: TransactionTemplate,
        @Qualifier("storeDirectOrderReads") reads: TransactionTemplate,
    ): Payments {
        return JpaPayments(
            client,
            payments,
            compensations,
            orders,
            storePayments,
            secretKey,
            writes,
            reads,
            Clock.systemDefaultZone(),
        )
    }

    @Bean
    fun paymentController(payments: Payments): PaymentController {
        return PaymentController(payments)
    }

    @Bean
    fun paymentInputExceptionHandler(): PaymentInputExceptionHandler {
        return PaymentInputExceptionHandler()
    }

    private companion object {
        fun requireSecretKey(secretKey: String?): String {
            if (secretKey == null || javaIsBlank(secretKey)) {
                throw IllegalArgumentException("An explicit Toss secret key is required")
            }
            return secretKey
        }

        fun parseProviderEndpoint(endpoint: String?): URI {
            val parsed: URI
            try {
                parsed = URI.create(endpoint)
            } catch (invalid: IllegalArgumentException) {
                throw IllegalArgumentException("An explicit Toss endpoint with /v1 is required")
            } catch (invalid: NullPointerException) {
                throw IllegalArgumentException("An explicit Toss endpoint with /v1 is required")
            }
            if (!parsed.isAbsolute || !("http" == parsed.scheme || "https" == parsed.scheme) ||
                parsed.host == null || parsed.userInfo != null || parsed.query != null ||
                parsed.fragment != null || parsed.port == 0 || parsed.port > 65535 || "/v1" != parsed.path
            ) {
                throw IllegalArgumentException("An explicit Toss endpoint with /v1 is required")
            }
            return parsed
        }
    }
}
