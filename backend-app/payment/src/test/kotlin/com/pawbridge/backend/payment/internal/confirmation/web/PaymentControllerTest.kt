package com.pawbridge.backend.payment.internal.confirmation.web

import com.pawbridge.backend.payment.internal.confirmation.application.Payments
import com.pawbridge.backend.payment.internal.confirmation.application.dto.TossPaymentConfirmRequest
import com.pawbridge.backend.payment.internal.confirmation.application.dto.TossPaymentResponse
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when` as whenever
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException

/** Direct controller boundary only; actual JWT/filter/path validation is owned by Boot integration tests. */
class PaymentControllerTest {
    @Test
    fun given__a_verified_principal__when__confirming__then__pass_that_identity_and_return_the_original_bare_response() {
        val payments = mock(Payments::class.java)
        val controller = PaymentController(payments)
        val request = TossPaymentConfirmRequest("synthetic-key", "order-uuid", 200L)
        val result = TossPaymentResponse.builder().status("ABORTED").build()
        whenever(payments.confirmPayment(7L, request)).thenReturn(result)

        val response = controller.confirmPayment(7L, request)

        assertThat(response.statusCode.value()).isEqualTo(200)
        assertThat(response.body).isSameAs(result)
        verify(payments).confirmPayment(7L, request)
        verifyNoMoreInteractions(payments)
    }

    @Test
    fun given__no_verified_principal__when__confirming__then__reject_before_the_application() {
        val payments = mock(Payments::class.java)
        val controller = PaymentController(payments)

        assertThatThrownBy {
            controller.confirmPayment(null, TossPaymentConfirmRequest("synthetic-key", "order-uuid", 200L))
        }.isInstanceOf(AuthenticationCredentialsNotFoundException::class.java)
        verifyNoInteractions(payments)
    }
}
