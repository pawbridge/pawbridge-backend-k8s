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
    fun `승인 요청이 주어졌을 때__전송하면__원래의 POST 경로와 Basic 헤더와 본문을 유지한다`() {
        // given
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

        // when
        val result = client.confirmPayment(
            "Basic synthetic-only",
            TossPaymentConfirmRequest("synthetic-key", "order-uuid", 200L),
        )

        // then
        assertThat(result!!.status).isEqualTo("READY")
        assertThat(result!!.orderId).isEqualTo("order-uuid")
        server.verify()
    }

    @Test
    fun `내부 보상 취소가 주어졌을 때__전송하면__원래의 취소 경로와 사유 본문을 유지한다`() {
        // given
        val builder = RestClient.builder().baseUrl("https://provider.invalid/v1")
        val server = MockRestServiceServer.bindTo(builder).build()
        val client = HttpTossPaymentsClient(builder.build())
        server.expect(requestTo("https://provider.invalid/v1/payments/synthetic-key/cancel"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Basic synthetic-only"))
            .andExpect(content().json("{\"cancelReason\":\"synthetic reason\"}"))
            .andRespond(withSuccess("{\"status\":\"CANCELED\"}", MediaType.APPLICATION_JSON))

        // when
        val result = client.cancelPayment(
            "Basic synthetic-only",
            "synthetic-key",
            TossPaymentCancelRequest("synthetic reason"),
        )

        // then
        assertThat(result!!.status).isEqualTo("CANCELED")
        server.verify()
    }

    @Test
    fun `승인 결과가 불명일 때__조회하면__같은 Basic 헤더로 결제 키로 GET 한다`() {
        // given
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

        // when
        val result = client.getPayment("Basic synthetic-only", "synthetic-key")

        // then
        assertThat(result!!.status).isEqualTo("DONE")
        assertThat(result!!.totalAmount).isEqualTo(200L)
        server.verify()
    }

    @Test
    fun `제공사 HTTP 실패가 주어졌을 때__전송하면__원래의 업무 오류 분기로 전파한다`() {
        // given
        val builder = RestClient.builder().baseUrl("https://provider.invalid/v1")
        val server = MockRestServiceServer.bindTo(builder).build()
        val client = HttpTossPaymentsClient(builder.build())
        server.expect(requestTo("https://provider.invalid/v1/payments/confirm"))
            .andRespond(withServerError())

        // when
        val thrown = assertThatThrownBy {
            client.confirmPayment("Basic synthetic-only", TossPaymentConfirmRequest("synthetic-key", "order-uuid", 200L))
        }

        // then
        thrown.isInstanceOf(HttpServerErrorException::class.java)
        server.verify()
    }
}
