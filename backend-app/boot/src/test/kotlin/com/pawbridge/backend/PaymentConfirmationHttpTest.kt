package com.pawbridge.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.payment.internal.confirmation.application.PaymentOrderAccessDeniedException
import com.pawbridge.backend.payment.internal.confirmation.application.PaymentOrderNotPayableException
import com.pawbridge.backend.payment.internal.confirmation.application.Payments
import com.pawbridge.backend.payment.internal.confirmation.application.dto.TossPaymentConfirmRequest
import com.pawbridge.backend.payment.internal.confirmation.application.dto.TossPaymentResponse
import com.pawbridge.backend.payment.internal.confirmation.web.PaymentController
import com.pawbridge.backend.payment.internal.confirmation.web.PaymentInputExceptionHandler
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(
    properties = ["pawbridge.payments.enabled=true"],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        PaymentController::class,
        PaymentInputExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class PaymentConfirmationHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var mapper: ObjectMapper

    @MockkBean
    lateinit var payments: Payments

    @Test
    fun `검증된 회원과 위조 헤더가 주어졌을 때__결제를 승인하면__JWT 신원과 토스 응답 원문을 쓴다`() {
        // given
        every { payments.confirmPayment(any(), any()) } returns
            TossPaymentResponse.builder()
                .paymentKey("synthetic-payment-key")
                .orderId("legacy-order-id")
                .currency("KRW")
                .method("카드")
                .totalAmount(200L)
                .balanceAmount(200L)
                .status("DONE")
                .build()

        // when
        val result = mvc.perform(
            verified("POST", "/api/v1/payments/confirm")
                .content("{\"paymentKey\":\"synthetic-payment-key\",\"orderId\":\"legacy-order-id\",\"amount\":200}"),
        )

        // then
        val response = result
            .andExpect(status().isOk())
            .andReturn()
        val input = slot<TossPaymentConfirmRequest>()
        verify(exactly = 1) { payments.confirmPayment(7L, capture(input)) }
        assertThat(input.captured.paymentKey).isEqualTo("synthetic-payment-key")
        assertThat(input.captured.orderId).isEqualTo("legacy-order-id")
        assertThat(input.captured.amount).isEqualTo(200L)
        confirmVerified(payments)
        val json = mapper.readTree(response.response.contentAsByteArray)
        assertThat(json.path("paymentKey").asText()).isEqualTo("synthetic-payment-key")
        assertThat(json.path("orderId").asText()).isEqualTo("legacy-order-id")
        assertThat(json.path("currency").asText()).isEqualTo("KRW")
        assertThat(json.path("method").asText()).isEqualTo("카드")
        assertThat(json.path("totalAmount").asLong()).isEqualTo(200L)
        assertThat(json.path("balanceAmount").asLong()).isEqualTo(200L)
        assertThat(json.path("status").asText()).isEqualTo("DONE")
        assertThat(json.has("data")).isFalse()
        assertThat(json.has("code")).isFalse()
    }

    @Test
    fun `생략된 널 허용 요청 필드가 주어졌을 때__결제를 승인하면__새 제약 없이 원래 DTO를 유지한다`() {
        // given
        every { payments.confirmPayment(any(), any()) } returns TossPaymentResponse.builder().status("ABORTED").build()

        // when
        val result = mvc.perform(verified("POST", "/api/v1/payments/confirm").content("{}"))

        // then
        val response = result
            .andExpect(status().isOk())
            .andReturn()
        val input = slot<TossPaymentConfirmRequest>()
        verify(exactly = 1) { payments.confirmPayment(7L, capture(input)) }
        assertThat(input.captured.paymentKey).isNull()
        assertThat(input.captured.orderId).isNull()
        assertThat(input.captured.amount).isNull()
        confirmVerified(payments)
        val json = mapper.readTree(response.response.contentAsByteArray)
        assertThat(json.path("status").asText()).isEqualTo("ABORTED")
        assertThat(json.has("data")).isFalse()
        assertThat(json.has("code")).isFalse()
        assertThat(json.has("failure")).isTrue()
        assertThat(json.get("failure").isNull()).isTrue()
    }

    @ParameterizedTest
    @CsvSource(
        "foreign,403,본인의 주문만 결제할 수 있습니다.",
        "not-pending,409,결제 대기 상태의 주문만 결제할 수 있습니다.",
    )
    fun `제공자 호출 전 주문 거절이 주어졌을 때__결제를 승인하면__범위 한정 문제 응답으로 바꾼다`(
        kind: String,
        status: Int,
        detail: String,
    ) {
        // given
        every { payments.confirmPayment(any(), any()) } throws
            if (kind == "foreign") PaymentOrderAccessDeniedException() else PaymentOrderNotPayableException()

        // when
        val result = mvc.perform(
            verified("POST", "/api/v1/payments/confirm")
                .content("{\"paymentKey\":\"synthetic-payment-key\",\"orderId\":\"legacy-order-id\",\"amount\":200}"),
        )

        // then
        val response = result
            .andExpect(status().`is`(status))
            .andReturn()
        val json = mapper.readTree(response.response.contentAsByteArray)
        assertThat(json.path("status").asInt()).isEqualTo(status)
        assertThat(json.path("detail").asText()).isEqualTo(detail)
        // A non-owner (including on an already paid order) never receives a stored payment key.
        assertThat(json.has("paymentKey")).isFalse()
        assertThat(response.response.contentAsString).doesNotContain("synthetic-payment-key")
    }

    @ParameterizedTest
    @ValueSource(strings = ["paymentKey", "orderId"])
    fun `BMP 밖 요청 필드가 주어졌을 때__검증된 회원이 승인하면__기존 범위 한정 검증 응답을 쓴다`(
        field: String,
    ) {
        // given
        val body = mapper.createObjectNode()
            .put("paymentKey", "synthetic-payment-key")
            .put("orderId", "legacy-order-id")
            .put("amount", 200)
        body.put(field, "😀")

        // when
        val result = mvc.perform(verified("POST", "/api/v1/payments/confirm").content(mapper.writeValueAsBytes(body)))

        // then
        val response = result
            .andExpect(status().isBadRequest())
            .andReturn()
        val json = mapper.readTree(response.response.contentAsByteArray)
        assertThat(json.path("detail").asText()).isEqualTo("일부 이모지 등 지원하지 않는 문자를 제외해 주세요.")
        confirmVerified(payments)
    }

    companion object {
        fun command(method: String, path: String): MockHttpServletRequestBuilder {
            return request(HttpMethod.valueOf(method), path)
                .header("X-User-Id", "99")
                .contentType(MediaType.APPLICATION_JSON)
        }

        fun verified(method: String, path: String): MockHttpServletRequestBuilder {
            return command(method, path).header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
        }
    }
}
