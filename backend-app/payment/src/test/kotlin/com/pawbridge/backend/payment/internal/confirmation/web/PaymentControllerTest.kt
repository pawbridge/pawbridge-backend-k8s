package com.pawbridge.backend.payment.internal.confirmation.web

import com.pawbridge.backend.payment.internal.confirmation.application.Payments
import com.pawbridge.backend.payment.internal.confirmation.application.dto.TossPaymentConfirmRequest
import com.pawbridge.backend.payment.internal.confirmation.application.dto.TossPaymentResponse
import com.pawbridge.backend.payment.support.TestFaker
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException

/** Direct controller boundary only; actual JWT/filter/path validation is owned by Boot integration tests. */
class PaymentControllerTest {
    private val faker = TestFaker.faker

    @Test
    fun `검증된 주체가 있을 때__결제를 승인하면__그 신원을 넘기고 원래의 단순 응답을 돌려준다`() {
        // given
        val payments = mockk<Payments>()
        val controller = PaymentController(payments)
        val request = TossPaymentConfirmRequest(faker.internet().uuid(), faker.internet().uuid(), 200L)
        val result = TossPaymentResponse.builder().status("ABORTED").build()

        every { payments.confirmPayment(any(), any()) } returns result

        // when
        val response = controller.confirmPayment(7L, request)

        // then
        assertThat(response.statusCode.value()).isEqualTo(200)
        assertThat(response.body).isSameAs(result)
        verify(exactly = 1) { payments.confirmPayment(7L, request) }
        confirmVerified(payments)
    }

    @Test
    fun `검증된 주체가 없을 때__결제를 승인하면__애플리케이션 호출 전에 거부한다`() {
        // given
        val payments = mockk<Payments>()
        val controller = PaymentController(payments)
        val request = TossPaymentConfirmRequest(faker.internet().uuid(), faker.internet().uuid(), 200L)

        // when
        val thrown = assertThatThrownBy { controller.confirmPayment(null, request) }

        // then
        thrown.isInstanceOf(AuthenticationCredentialsNotFoundException::class.java)
        confirmVerified(payments)
    }
}
