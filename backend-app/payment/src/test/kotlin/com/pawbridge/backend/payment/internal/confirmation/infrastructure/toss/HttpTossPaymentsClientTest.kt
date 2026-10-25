package com.pawbridge.backend.payment.internal.confirmation.infrastructure.toss

import com.pawbridge.backend.payment.internal.confirmation.application.dto.TossPaymentCancelRequest
import com.pawbridge.backend.payment.internal.confirmation.application.dto.TossPaymentConfirmRequest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.RestClient

/** In-memory HTTP expectations; no network or provider call. */
class HttpTossPaymentsClientTest {
    @Test
    fun given__a_synthetic_confirmation__when__sending__then__preserve_original_post_path_basic_header_and_body() {
        val builder = RestClient.builder().baseUrl("https://provider.invalid/v1")
        val server = MockRestServiceServer.bindTo(builder).build()
        val client = HttpTossPaymentsClient(builder.build())
        server.expect(requestTo("https://provider.invalid/v1/payments/confirm"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Basic synthetic-only"))
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(content().json("{\"paymentKey\":\"synthetic-key\",\"orderId\":\"order-uuid\",\"amount\":200}"))
            .andRespond(
                withSuccess(
                    "{\"paymentKey\":\"synthetic-key\",\"orderId\":\"order-uuid\",\"status\":\"READY\"}",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val result = client.confirmPayment(
            "Basic synthetic-only",
            TossPaymentConfirmRequest("synthetic-key", "order-uuid", 200L),
        )

        assertThat(result!!.status).isEqualTo("READY")
        assertThat(result!!.orderId).isEqualTo("order-uuid")
        server.verify()
    }

    @Test
    fun given__an_internal_compensation__when__sending__then__preserve_original_cancel_path_and_reason_body() {
        val builder = RestClient.builder().baseUrl("https://provider.invalid/v1")
        val server = MockRestServiceServer.bindTo(builder).build()
        val client = HttpTossPaymentsClient(builder.build())
        server.expect(requestTo("https://provider.invalid/v1/payments/synthetic-key/cancel"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Basic synthetic-only"))
            .andExpect(content().json("{\"cancelReason\":\"synthetic reason\"}"))
            .andRespond(withSuccess("{\"status\":\"CANCELED\"}", MediaType.APPLICATION_JSON))

        assertThat(
            client.cancelPayment(
                "Basic synthetic-only",
                "synthetic-key",
                TossPaymentCancelRequest("synthetic reason"),
            )!!.status,
        ).isEqualTo("CANCELED")
        server.verify()
    }

    @Test
    fun given__an_unknown_confirmation_result__when__looking_up__then__get_the_payment_by_key_with_the_same_basic_header() {
        val builder = RestClient.builder().baseUrl("https://provider.invalid/v1")
        val server = MockRestServiceServer.bindTo(builder).build()
        val client = HttpTossPaymentsClient(builder.build())
        server.expect(requestTo("https://provider.invalid/v1/payments/synthetic-key"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Basic synthetic-only"))
            .andRespond(
                withSuccess(
                    "{\"paymentKey\":\"synthetic-key\",\"orderId\":\"order-uuid\",\"status\":\"DONE\",\"totalAmount\":200}",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val result = client.getPayment("Basic synthetic-only", "synthetic-key")

        assertThat(result!!.status).isEqualTo("DONE")
        assertThat(result!!.totalAmount).isEqualTo(200L)
        server.verify()
    }

    @Test
    fun given__a_provider_http_failure__when__sending__then__propagate_to_the_original_business_error_branch() {
        val builder = RestClient.builder().baseUrl("https://provider.invalid/v1")
        val server = MockRestServiceServer.bindTo(builder).build()
        val client = HttpTossPaymentsClient(builder.build())
        server.expect(requestTo("https://provider.invalid/v1/payments/confirm"))
            .andRespond(withServerError())

        assertThatThrownBy {
            client.confirmPayment("Basic synthetic-only", TossPaymentConfirmRequest("synthetic-key", "order-uuid", 200L))
        }.isInstanceOf(HttpServerErrorException::class.java)
        server.verify()
    }
}
