package com.pawbridge.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import com.pawbridge.backend.store.api.StorePaymentEvents
import com.pawbridge.backend.store.internal.payment.web.MockOrderPaymentController
import com.pawbridge.backend.store.internal.payment.web.MockOrderPaymentExceptionHandler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.HttpMethod
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(
    properties = ["pawbridge.store-mock-payment.enabled=TrUe"],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        MockOrderPaymentController::class,
        MockOrderPaymentExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class StoreMockPaymentHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var mapper: ObjectMapper

    @MockitoBean
    lateinit var payments: StorePaymentEvents

    @ParameterizedTest
    @ValueSource(strings = ["ROLE_USER", "ROLE_ADMIN", "ROLE_SHELTER"])
    fun given__any_verified_role__when__paying_any_order_id__then__run_the_original_business_without_owner_check(
        role: String,
    ) {
        val result = mvc.perform(
            command("POST", "/api/v1/orders/41/payment")
                .header("Authorization", "Bearer " + F3TestInputs.token(7, role)),
        )
            .andExpect(status().isOk())
            .andReturn()
        verify(payments).processPayment(41L)
        verifyNoMoreInteractions(payments)
        assertThat(result.response.contentAsByteArray).isEmpty()
    }

    @Test
    fun given__only_a_forged_identity_header__when__paying__then__reject_before_business() {
        mvc.perform(command("POST", "/api/v1/orders/41/payment")).andExpect(status().isUnauthorized())
        verifyNoInteractions(payments)
    }

    @Test
    fun given__an_invalid_bearer__when__paying__then__reject_before_business() {
        mvc.perform(
            command("POST", "/api/v1/orders/41/payment").header("Authorization", "Bearer synthetic-invalid-token"),
        )
            .andExpect(status().isUnauthorized())
        verifyNoInteractions(payments)
    }

    @Test
    fun given__a_missing_order__when__paying__then__map_the_original_illegal_argument_to_c001() {
        doThrow(IllegalArgumentException("Order not found: 41")).`when`(payments).processPayment(41L)
        val json = mapper.readTree(
            mvc.perform(verified("POST", "/api/v1/orders/41/payment"))
                .andExpect(status().isBadRequest())
                .andReturn().response.contentAsByteArray,
        )
        assertThat(json.path("message").asText()).isEqualTo("Invalid Input Value")
        assertThat(json.path("code").asText()).isEqualTo("C001")
        assertThat(json.path("errors").isArray).isTrue()
        assertThat(json.path("errors")).isEmpty()
    }

    @ParameterizedTest
    @ValueSource(strings = ["state", "runtime"])
    fun given__a_non_pending_order_or_event_failure__when__paying__then__map_to_the_original_server_error(
        failure: String,
    ) {
        val thrown: RuntimeException = if (failure == "state") {
            IllegalStateException("Order is not in PENDING state. Current: PAID")
        } else {
            RuntimeException("Failed to publish payment event")
        }
        doThrow(thrown).`when`(payments).processPayment(41L)
        val json = mapper.readTree(
            mvc.perform(verified("POST", "/api/v1/orders/41/payment"))
                .andExpect(status().isInternalServerError())
                .andReturn().response.contentAsByteArray,
        )
        assertThat(json.path("message").asText()).isEqualTo("Server Error")
        assertThat(json.path("code").asText()).isEqualTo("C004")
        assertThat(json.toString()).doesNotContain("PENDING").doesNotContain("publish")
    }

    @Test
    fun given__a_non_numeric_order_id__when__paying__then__return_the_original_type_error_before_business() {
        val json = mapper.readTree(
            mvc.perform(verified("POST", "/api/v1/orders/abc/payment"))
                .andExpect(status().isBadRequest())
                .andReturn().response.contentAsByteArray,
        )
        assertThat(json.path("message").asText()).isEqualTo("Invalid Type Value")
        assertThat(json.path("code").asText()).isEqualTo("C005")
        assertThat(json.path("errors").get(0).path("field").asText()).isEqualTo("orderId")
        assertThat(json.path("errors").get(0).path("value").asText()).isEqualTo("abc")
        assertThat(json.path("errors").get(0).path("reason").asText()).isEqualTo("typeMismatch")
        verifyNoInteractions(payments)
    }

    @ParameterizedTest
    @CsvSource(
        "GET,/api/v1/orders/41/payment", "PUT,/api/v1/orders/41/payment", "PATCH,/api/v1/orders/41/payment",
        "DELETE,/api/v1/orders/41/payment", "POST,/api/orders/41/payment", "POST,/api/v1/orders/41/payment/extra",
        "POST,/api/v1/orders/payment", "POST,/api/v1/orders/41/payments",
    )
    fun given__a_verified_member__when__using_an_unregistered_method_or_alias__then__deny_before_business(
        method: String,
        path: String,
    ) {
        mvc.perform(verified(method, path)).andExpect(status().isForbidden())
        verifyNoInteractions(payments)
    }

    companion object {
        fun command(method: String, path: String): MockHttpServletRequestBuilder {
            return request(HttpMethod.valueOf(method), path).header("X-User-Id", "99")
        }

        fun verified(method: String, path: String): MockHttpServletRequestBuilder {
            return command(method, path).header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_USER"))
        }
    }
}

@WebMvcTest(excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class])
@ContextConfiguration(
    classes = [
        MockOrderPaymentController::class,
        MockOrderPaymentExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class StoreMockPaymentDisabledHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockitoBean
    lateinit var payments: StorePaymentEvents

    @Test
    fun given__no_mock_payment_flag__when__a_verified_member_pays__then__deny_before_business() {
        mvc.perform(StoreMockPaymentHttpTest.verified("POST", "/api/v1/orders/41/payment"))
            .andExpect(status().isForbidden())
        verifyNoInteractions(payments)
    }
}

@WebMvcTest(
    properties = [
        "pawbridge.store-mock-payment.enabled=on",
        "pawbridge.payments.enabled=true",
        "pawbridge.store-payment-events.enabled=true",
    ],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        MockOrderPaymentController::class,
        MockOrderPaymentExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class StoreMockPaymentAliasDisabledHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockitoBean
    lateinit var payments: StorePaymentEvents

    @Test
    fun given__a_noncanonical_flag_and_other_payment_flags__when__a_verified_member_pays__then__deny_before_business() {
        mvc.perform(StoreMockPaymentHttpTest.verified("POST", "/api/v1/orders/41/payment"))
            .andExpect(status().isForbidden())
        verifyNoInteractions(payments)
    }
}
