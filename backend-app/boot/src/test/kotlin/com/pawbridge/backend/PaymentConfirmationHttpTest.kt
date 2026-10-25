package com.pawbridge.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.payment.internal.confirmation.application.PaymentOrderAccessDeniedException
import com.pawbridge.backend.payment.internal.confirmation.application.PaymentOrderNotPayableException
import com.pawbridge.backend.payment.internal.confirmation.application.Payments
import com.pawbridge.backend.payment.internal.confirmation.application.dto.TossPaymentConfirmRequest
import com.pawbridge.backend.payment.internal.confirmation.application.dto.TossPaymentResponse
import com.pawbridge.backend.payment.internal.confirmation.web.PaymentController
import com.pawbridge.backend.payment.internal.confirmation.web.PaymentInputExceptionHandler
import com.pawbridge.backend.support.anyNonNull
import com.pawbridge.backend.support.capture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.`when` as whenever
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.bean.override.mockito.MockitoBean
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

    @MockitoBean
    lateinit var payments: Payments

    @Test
    fun given__a_verified_member_and_forged_header__when__confirming__then__use_the_jwt_identity_and_bare_toss_response() {
        whenever(payments.confirmPayment(eq(7L), anyNonNull<TossPaymentConfirmRequest>())).thenReturn(
            TossPaymentResponse.builder()
                .paymentKey("synthetic-payment-key")
                .orderId("legacy-order-id")
                .currency("KRW")
                .method("카드")
                .totalAmount(200L)
                .balanceAmount(200L)
                .status("DONE")
                .build(),
        )

        val result = mvc.perform(
            verified("POST", "/api/v1/payments/confirm")
                .content("{\"paymentKey\":\"synthetic-payment-key\",\"orderId\":\"legacy-order-id\",\"amount\":200}"),
        )
            .andExpect(status().isOk())
            .andReturn()

        val input: ArgumentCaptor<TossPaymentConfirmRequest> = ArgumentCaptor.forClass(TossPaymentConfirmRequest::class.java)
        verify(payments).confirmPayment(eq(7L), capture(input))
        assertThat(input.value.paymentKey).isEqualTo("synthetic-payment-key")
        assertThat(input.value.orderId).isEqualTo("legacy-order-id")
        assertThat(input.value.amount).isEqualTo(200L)
        verifyNoMoreInteractions(payments)
        val json = mapper.readTree(result.response.contentAsByteArray)
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
    fun given__omitted_nullable_request_fields__when__confirming__then__preserve_the_original_dto_without_new_constraints() {
        whenever(payments.confirmPayment(eq(7L), anyNonNull<TossPaymentConfirmRequest>()))
            .thenReturn(TossPaymentResponse.builder().status("ABORTED").build())
        val result = mvc.perform(verified("POST", "/api/v1/payments/confirm").content("{}"))
            .andExpect(status().isOk())
            .andReturn()
        val input: ArgumentCaptor<TossPaymentConfirmRequest> = ArgumentCaptor.forClass(TossPaymentConfirmRequest::class.java)
        verify(payments).confirmPayment(eq(7L), capture(input))
        assertThat(input.value.paymentKey).isNull()
        assertThat(input.value.orderId).isNull()
        assertThat(input.value.amount).isNull()
        verifyNoMoreInteractions(payments)
        val json = mapper.readTree(result.response.contentAsByteArray)
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
    fun given__a_pre_provider_order_rejection__when__confirming__then__map_it_to_a_scoped_problem_response(
        kind: String,
        status: Int,
        detail: String,
    ) {
        whenever(payments.confirmPayment(eq(7L), anyNonNull<TossPaymentConfirmRequest>())).thenThrow(
            if (kind == "foreign") PaymentOrderAccessDeniedException() else PaymentOrderNotPayableException(),
        )
        val result = mvc.perform(
            verified("POST", "/api/v1/payments/confirm")
                .content("{\"paymentKey\":\"synthetic-payment-key\",\"orderId\":\"legacy-order-id\",\"amount\":200}"),
        )
            .andExpect(status().`is`(status))
            .andReturn()
        val json = mapper.readTree(result.response.contentAsByteArray)
        assertThat(json.path("status").asInt()).isEqualTo(status)
        assertThat(json.path("detail").asText()).isEqualTo(detail)
        // A non-owner (including on an already paid order) never receives a stored payment key.
        assertThat(json.has("paymentKey")).isFalse()
        assertThat(result.response.contentAsString).doesNotContain("synthetic-payment-key")
    }

    @Test
    fun given__only_a_forged_identity_header__when__confirming__then__reject_before_the_payment_application() {
        mvc.perform(command("POST", "/api/v1/payments/confirm").content("{}"))
            .andExpect(status().isUnauthorized())
        verifyNoInteractions(payments)
    }

    @Test
    fun given__an_invalid_bearer_and_forged_header__when__confirming__then__reject_before_the_payment_application() {
        mvc.perform(
            command("POST", "/api/v1/payments/confirm")
                .header("Authorization", "Bearer synthetic-invalid-token")
                .content("{}"),
        )
            .andExpect(status().isUnauthorized())
        verifyNoInteractions(payments)
    }

    @ParameterizedTest
    @ValueSource(strings = ["paymentKey", "orderId"])
    fun given__a_non_bmp_request_field__when__a_verified_member_confirms__then__use_the_existing_scoped_validation_response(
        field: String,
    ) {
        val body = mapper.createObjectNode()
            .put("paymentKey", "synthetic-payment-key")
            .put("orderId", "legacy-order-id")
            .put("amount", 200)
        body.put(field, "😀")
        val result = mvc.perform(verified("POST", "/api/v1/payments/confirm").content(mapper.writeValueAsBytes(body)))
            .andExpect(status().isBadRequest())
            .andReturn()
        val json = mapper.readTree(result.response.contentAsByteArray)
        assertThat(json.path("detail").asText()).isEqualTo("일부 이모지 등 지원하지 않는 문자를 제외해 주세요.")
        verifyNoInteractions(payments)
    }

    @ParameterizedTest
    @CsvSource(
        "GET,/api/v1/payments/confirm",
        "PUT,/api/v1/payments/confirm",
        "PATCH,/api/v1/payments/confirm",
        "DELETE,/api/v1/payments/confirm",
        "POST,/api/payments/confirm",
        "POST,/api/v1/payments",
        "POST,/api/v1/payments/confirm/extra",
    )
    fun given__a_verified_member__when__using_an_unregistered_method_or_alias__then__deny_before_business(
        method: String,
        path: String,
    ) {
        mvc.perform(verified(method, path).content("{}")).andExpect(status().isForbidden())
        verifyNoInteractions(payments)
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

@WebMvcTest(excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class])
@ContextConfiguration(
    classes = [
        PaymentController::class,
        PaymentInputExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class PaymentConfirmationDisabledHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockitoBean
    lateinit var payments: Payments

    @Test
    fun given__no_payment_flag__when__a_verified_member_confirms__then__deny_before_business() {
        mvc.perform(PaymentConfirmationHttpTest.verified("POST", "/api/v1/payments/confirm").content("{}"))
            .andExpect(status().isForbidden())
        verifyNoInteractions(payments)
    }
}

@WebMvcTest(
    properties = ["pawbridge.payments.enabled=on"],
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
class PaymentConfirmationAliasDisabledHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockitoBean
    lateinit var payments: Payments

    @Test
    fun given__a_noncanonical_payment_flag__when__a_verified_member_confirms__then__deny_before_business() {
        mvc.perform(PaymentConfirmationHttpTest.verified("POST", "/api/v1/payments/confirm").content("{}"))
            .andExpect(status().isForbidden())
        verifyNoInteractions(payments)
    }
}
