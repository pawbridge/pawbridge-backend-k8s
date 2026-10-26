package com.pawbridge.backend.payment.internal.confirmation.infrastructure

import com.pawbridge.backend.payment.internal.confirmation.application.PaymentOrderAccessDeniedException
import com.pawbridge.backend.payment.internal.confirmation.application.PaymentOrderNotPayableException
import com.pawbridge.backend.payment.internal.confirmation.application.dto.TossPaymentConfirmRequest
import com.pawbridge.backend.payment.internal.confirmation.application.dto.TossPaymentResponse
import com.pawbridge.backend.payment.internal.confirmation.infrastructure.jpa.model.Payment
import com.pawbridge.backend.payment.internal.confirmation.infrastructure.jpa.model.PaymentCompensationRecord
import com.pawbridge.backend.payment.internal.confirmation.infrastructure.jpa.model.PaymentStatus
import com.pawbridge.backend.payment.internal.confirmation.infrastructure.jpa.repository.PaymentCompensationRecordRepository
import com.pawbridge.backend.payment.internal.confirmation.infrastructure.jpa.repository.PaymentRepository
import com.pawbridge.backend.payment.internal.confirmation.infrastructure.toss.TossPaymentsClient
import com.pawbridge.backend.store.api.StorePaymentEvents
import com.pawbridge.backend.store.api.StorePaymentEvents.Completion
import com.pawbridge.backend.store.api.StorePaymentOrders
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Optional

@ExtendWith(OutputCaptureExtension::class)
class JpaPaymentsTest {
    @Test
    fun `본인의 결제 대기 주문일 때__제공사가 승인하면__트랜잭션 밖에서 호출하고 결제와 주문을 한 쓰기 트랜잭션에서 반영한다`() {
        // given
        val f = Fixture()
        val response = f.done()
        val saved = slot<Payment>()

        every { f.orders.getOrder("order-uuid") } answers {
            f.outside("order")
            f.pendingOrder
        }
        every { f.toss.confirmPayment(any(), any()) } answers {
            f.outside("provider")
            response
        }
        every { f.store.completePayment(any(), any()) } answers {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue()
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse()
            f.events.add("complete:" + secondArg<Long>())
            StorePaymentEvents.Completed(Completion.APPLIED, Runnable { f.outside("rank") })
        }
        every { f.payments.findFirstByOrderIdAndStatusOrderByIdAsc("order-uuid", PaymentStatus.DONE) } answers {
            if (f.manager.writing) {
                f.events.add("done-check")
            }
            Optional.empty()
        }
        every { f.payments.saveAndFlush(any<Payment>()) } answers {
            f.events.add("payment")
            firstArg()
        }

        // when
        val result = f.service.confirmPayment(7L, f.request)

        // then
        assertThat(result).isSameAs(response)
        assertThat(f.events).containsExactly(
            "order", "begin:ro", "commit", "provider", "begin:rw", "complete:200", "done-check", "payment", "commit", "rank",
        )
        verify(exactly = 1) { f.payments.saveAndFlush(capture(saved)) }
        val payment = saved.captured
        assertThat(payment.userId).isEqualTo(7L)
        assertThat(payment.orderId).isEqualTo("order-uuid")
        assertThat(payment.paymentKey).isEqualTo("synthetic-key")
        assertThat(payment.status).isEqualTo(PaymentStatus.DONE)
        assertThat(payment.amount).isEqualTo(200L)
        assertThat(payment.requestedAt).isEqualTo(response.requestedAt!!.toLocalDateTime())
        assertThat(payment.approvedAt).isEqualTo(response.approvedAt!!.toLocalDateTime())
        verify(exactly = 1) {
            f.toss.confirmPayment("Basic c3ludGhldGljLXNlY3JldDo=", match { it === f.request })
        }
        confirmVerified(f.toss)
        confirmVerified(f.compensations)
        verify(exactly = 0) { f.store.failPayment(any()) }
    }

    @Test
    fun `이미 완료된 결제가 있을 때__소유자가 같은 주문과 금액으로 반복하면__제공사 호출 없이 저장된 부분집합을 돌려준다`() {
        // given
        val f = Fixture()
        val existing = f.existing("stored-synthetic-key")

        every { f.orders.getOrder("order-uuid") } answers {
            f.outside("order")
            StorePaymentOrders.Order(8L, "order-uuid", 7L, 200L, "PAID")
        }
        every { f.payments.findFirstByOrderIdAndStatusOrderByIdAsc("order-uuid", PaymentStatus.DONE) } returns
            Optional.of(existing)

        // when
        val result = f.service.confirmPayment(7L, f.request)

        // then
        assertThat(result.status).isEqualTo("DONE")
        assertThat(result.paymentKey).isEqualTo("stored-synthetic-key")
        assertThat(result.totalAmount).isEqualTo(200L)
        assertThat(result.requestedAt).isEqualTo(existing.requestedAt!!.atOffset(ZoneOffset.ofHours(9)))
        assertThat(result.approvedAt).isEqualTo(existing.approvedAt!!.atOffset(ZoneOffset.ofHours(9)))
        assertThat(f.events).containsExactly("order", "begin:ro", "commit")
        confirmVerified(f.toss, f.store)
    }

    @Test
    fun `다른 회원의 이미 결제된 주문일 때__승인하면__저장된 결제를 드러내기 전에 403으로 거부한다`() {
        // given
        val f = Fixture()

        every { f.orders.getOrder("order-uuid") } answers {
            f.outside("order")
            StorePaymentOrders.Order(8L, "order-uuid", 7L, 200L, "PAID")
        }

        // when
        val thrown = assertThatThrownBy { f.service.confirmPayment(8L, f.request) }

        // then
        thrown.isInstanceOf(PaymentOrderAccessDeniedException::class.java)
            .hasMessageNotContaining("stored-synthetic-key")
        assertThat(f.events).containsExactly("order")
        verify(exactly = 0) { f.payments.findFirstByOrderIdAndStatusOrderByIdAsc(any(), any()) }
        confirmVerified(f.toss, f.store)
    }

    @Test
    fun `금액이 다른 완료 결제가 이미 있을 때__반복하면__원래의 불일치 실패를 유지한다`() {
        // given
        val f = Fixture()
        val existing = f.existing("stored-synthetic-key")
        val request = TossPaymentConfirmRequest.builder().paymentKey("synthetic-key").orderId("order-uuid").amount(201L).build()

        every { f.orders.getOrder("order-uuid") } answers {
            f.outside("order")
            f.pendingOrder
        }
        every { f.payments.findFirstByOrderIdAndStatusOrderByIdAsc("order-uuid", PaymentStatus.DONE) } returns
            Optional.of(existing)

        // when
        val thrown = assertThatThrownBy { f.service.confirmPayment(7L, request) }

        // then
        thrown.isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Payment exists but amount mismatch")
        confirmVerified(f.toss, f.store)
    }

    @Test
    fun `다른 회원의 주문일 때__승인하면__제공사 호출 전에 거부한다`() {
        // given
        val f = Fixture()

        every { f.orders.getOrder("order-uuid") } answers {
            f.outside("order")
            f.pendingOrder
        }

        // when
        val thrown = assertThatThrownBy { f.service.confirmPayment(8L, f.request) }

        // then
        thrown.isInstanceOf(PaymentOrderAccessDeniedException::class.java)
        confirmVerified(f.toss, f.store)
    }

    @ParameterizedTest
    @ValueSource(strings = ["PAID", "CANCELLED", "COMPLETED", "FAILED"])
    fun `결제 대기가 아닌 주문일 때__승인하면__제공사 호출 전에 거부한다`(status: String) {
        // given
        val f = Fixture()

        every { f.orders.getOrder("order-uuid") } answers {
            f.outside("order")
            StorePaymentOrders.Order(8L, "order-uuid", 7L, 200L, status)
        }
        every { f.payments.findFirstByOrderIdAndStatusOrderByIdAsc("order-uuid", PaymentStatus.DONE) } returns
            Optional.empty()

        // when
        val thrown = assertThatThrownBy { f.service.confirmPayment(7L, f.request) }

        // then
        thrown.isInstanceOf(PaymentOrderNotPayableException::class.java)
        confirmVerified(f.toss, f.store)
    }

    @Test
    fun `주문 금액이 다를 때__승인하면__제공사 호출 전에 원래의 불일치 실패를 유지한다`() {
        // given
        val f = Fixture()

        every { f.orders.getOrder("order-uuid") } answers {
            f.outside("order")
            StorePaymentOrders.Order(8L, "order-uuid", 7L, 300L, "PENDING")
        }
        every { f.payments.findFirstByOrderIdAndStatusOrderByIdAsc("order-uuid", PaymentStatus.DONE) } returns
            Optional.empty()

        // when
        val thrown = assertThatThrownBy { f.service.confirmPayment(7L, f.request) }

        // then
        thrown.isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Payment Amount Mismatch")
        confirmVerified(f.toss, f.store)
    }

    @Test
    fun `제공사가 명확히 거절했을 때__승인하면__대기 주문을 한 쓰기 트랜잭션에서 취소하고 ABORTED를 돌려준다`() {
        // given
        val f = Fixture()

        every { f.orders.getOrder("order-uuid") } answers {
            f.outside("order")
            f.pendingOrder
        }
        every { f.payments.findFirstByOrderIdAndStatusOrderByIdAsc("order-uuid", PaymentStatus.DONE) } returns
            Optional.empty()
        every { f.toss.confirmPayment(any(), any()) } answers {
            f.outside("provider")
            throw f.clientError(HttpStatus.BAD_REQUEST, "{\"code\":\"REJECT_CARD_PAYMENT\"}")
        }
        every { f.store.failPayment(any()) } answers {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue()
            f.events.add("fail")
            true
        }

        // when
        val result = f.service.confirmPayment(7L, f.request)

        // then
        assertThat(result.status).isEqualTo("ABORTED")
        assertThat(result.orderId).isEqualTo("order-uuid")
        assertThat(result.paymentKey).isEqualTo("synthetic-key")
        assertThat(f.events).containsExactly("order", "begin:ro", "commit", "provider", "begin:rw", "fail", "commit")
        verify(exactly = 0) { f.toss.getPayment(any(), any()) }
        verify(exactly = 0) { f.toss.cancelPayment(any(), any(), any()) }
        verify(exactly = 0) { f.store.completePayment(any(), any()) }
        confirmVerified(f.compensations)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "401:UNAUTHORIZED_KEY", "429:", "403:FORBIDDEN_REQUEST", "400:INVALID_API_KEY",
            "400:NOT_FOUND_TERMINAL_ID", "403:INCORRECT_BASIC_AUTH_FORMAT",
        ],
    )
    fun `토스가 요청을 처리하지 않았을 때__승인하면__취소나 기록 없이 주문을 대기 상태로 둔다`(
        input: String,
    ) {
        // given
        val f = Fixture()
        val parts = input.split(":")
        val body = if (parts[1].isEmpty()) "" else "{\"code\":\"" + parts[1] + "\",\"message\":\"synthetic\"}"

        every { f.orders.getOrder("order-uuid") } answers {
            f.outside("order")
            f.pendingOrder
        }
        every { f.payments.findFirstByOrderIdAndStatusOrderByIdAsc("order-uuid", PaymentStatus.DONE) } returns
            Optional.empty()
        every { f.toss.confirmPayment(any(), any()) } answers {
            f.outside("provider")
            throw f.clientError(HttpStatus.valueOf(parts[0].toInt()), body)
        }

        // when
        val thrown = assertThatThrownBy { f.service.confirmPayment(7L, f.request) }

        // then
        thrown.hasMessage("Payment provider did not process the request. The order remains pending.")
        assertThat(f.events).containsExactly("order", "begin:ro", "commit", "provider")
        confirmVerified(f.store, f.compensations)
        verify(exactly = 0) { f.toss.getPayment(any(), any()) }
        verify(exactly = 0) { f.toss.cancelPayment(any(), any(), any()) }
    }

    @Test
    fun `이미 처리되었고 기록된 결제가 있을 때__승인하면__짧은 읽기에서 저장된 DONE을 돌려준다`() {
        // given
        val f = Fixture()
        val existing = f.existing("synthetic-key")

        every { f.orders.getOrder("order-uuid") } answers {
            f.outside("order")
            f.pendingOrder
        }
        every { f.payments.findFirstByOrderIdAndStatusOrderByIdAsc("order-uuid", PaymentStatus.DONE) } returnsMany
            listOf(Optional.empty(), Optional.of(existing))
        every { f.toss.confirmPayment(any(), any()) } answers {
            f.outside("provider")
            throw f.clientError(HttpStatus.BAD_REQUEST, "{\"code\":\"ALREADY_PROCESSED_PAYMENT\"}")
        }

        // when
        val result = f.service.confirmPayment(7L, f.request)

        // then
        assertThat(result.status).isEqualTo("DONE")
        assertThat(result.paymentKey).isEqualTo("synthetic-key")
        assertThat(f.events).containsExactly("order", "begin:ro", "commit", "provider", "begin:ro", "commit")
        confirmVerified(f.store, f.compensations)
    }

    @Test
    fun `이미 처리되었으나 기록된 결제가 없을 때__조회 결과가 DONE이면__쓰기 트랜잭션에서 반영한다`() {
        // given
        val f = Fixture()
        val looked = f.done()

        every { f.orders.getOrder("order-uuid") } answers {
            f.outside("order")
            f.pendingOrder
        }
        every { f.payments.findFirstByOrderIdAndStatusOrderByIdAsc("order-uuid", PaymentStatus.DONE) } answers {
            if (f.manager.writing) {
                f.events.add("done-check")
            }
            Optional.empty()
        }
        every { f.toss.confirmPayment(any(), any()) } answers {
            f.outside("provider")
            throw IllegalStateException("S008 synthetic")
        }
        every { f.toss.getPayment(any(), "synthetic-key") } answers {
            f.outside("lookup")
            looked
        }
        every { f.store.completePayment(any(), any()) } answers {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue()
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse()
            f.events.add("complete:" + secondArg<Long>())
            StorePaymentEvents.Completed(Completion.APPLIED, Runnable { f.outside("rank") })
        }
        every { f.payments.saveAndFlush(any<Payment>()) } answers {
            f.events.add("payment")
            firstArg()
        }

        // when
        val result = f.service.confirmPayment(7L, f.request)

        // then
        assertThat(result).isSameAs(looked)
        assertThat(f.events).containsExactly(
            "order", "begin:ro", "commit", "provider", "begin:ro", "commit", "lookup",
            "begin:rw", "complete:200", "done-check", "payment", "commit", "rank",
        )
        confirmVerified(f.compensations)
    }

    @ParameterizedTest
    @ValueSource(strings = ["none", "ABORTED"])
    fun `이미 처리되었으나 기록된 결제가 없을 때__조회 결과가 DONE이 아니면__기록하고 주문을 취소하지 않는다`(
        looked: String,
        output: CapturedOutput,
    ) {
        // given
        val f = Fixture()
        val savedRecord = slot<PaymentCompensationRecord>()

        every { f.orders.getOrder("order-uuid") } answers {
            f.outside("order")
            f.pendingOrder
        }
        every { f.payments.findFirstByOrderIdAndStatusOrderByIdAsc("order-uuid", PaymentStatus.DONE) } returns
            Optional.empty()
        every { f.toss.confirmPayment(any(), any()) } answers {
            f.outside("provider")
            throw f.clientError(HttpStatus.BAD_REQUEST, "{\"code\":\"ALREADY_PROCESSED_PAYMENT\"}")
        }
        if (looked == "none") {
            every { f.toss.getPayment(any(), any()) } returns null
        } else {
            every { f.toss.getPayment(any(), any()) } returns
                TossPaymentResponse.builder().paymentKey("synthetic-key").orderId("order-uuid").status(looked).build()
        }
        every { f.compensations.saveAndFlush(any<PaymentCompensationRecord>()) } answers {
            val record = firstArg<PaymentCompensationRecord>()
            ReflectionTestUtils.setField(record, "id", 41L)
            record
        }

        // when
        val thrown = assertThatThrownBy { f.service.confirmPayment(7L, f.request) }

        // then
        thrown.hasMessage("Payment result is unknown. The order remains pending.")
        confirmVerified(f.store)
        verify(exactly = 0) { f.toss.cancelPayment(any(), any(), any()) }
        verify(exactly = 1) { f.compensations.saveAndFlush(capture(savedRecord)) }
        assertThat(savedRecord.captured.reason).isEqualTo("ALREADY_PROCESSED_UNRECORDED")
        assertThat(output.all).contains(
            "PAYMENT_COMPENSATION_REQUIRED paymentKey=synthetic-key orderId=order-uuid amount=200 userId=7 stage=UNKNOWN_RESULT reason=ALREADY_PROCESSED_UNRECORDED recordId=41",
        )
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "400:ALREADY_PROCESSING_REQUEST", "400:", "408:", "400:PROVIDER_ERROR", "400:INVALID_REQUEST",
            "400:CARD_PROCESSING_ERROR", "400:SOME_FUTURE_CODE", "404:NOT_FOUND_PAYMENT",
        ],
    )
    fun `목록에 없는 4xx일 때__승인하면__조회로 해소하고 주문을 대기 상태로 둔다`(
        input: String,
    ) {
        // given
        val f = Fixture()
        val parts = input.split(":")
        val body = if (parts[1].isEmpty()) "<html>gateway</html>" else "{\"code\":\"" + parts[1] + "\",\"message\":\"synthetic\"}"
        val savedRecord = slot<PaymentCompensationRecord>()

        every { f.orders.getOrder("order-uuid") } answers {
            f.outside("order")
            f.pendingOrder
        }
        every { f.payments.findFirstByOrderIdAndStatusOrderByIdAsc("order-uuid", PaymentStatus.DONE) } returns
            Optional.empty()
        every { f.toss.confirmPayment(any(), any()) } answers {
            f.outside("provider")
            throw f.clientError(HttpStatus.valueOf(parts[0].toInt()), body)
        }
        every { f.toss.getPayment(any(), any()) } returns
            TossPaymentResponse.builder().paymentKey("synthetic-key").orderId("order-uuid").status("IN_PROGRESS").build()
        every { f.compensations.saveAndFlush(any<PaymentCompensationRecord>()) } answers {
            val record = firstArg<PaymentCompensationRecord>()
            ReflectionTestUtils.setField(record, "id", 41L)
            record
        }

        // when
        val thrown = assertThatThrownBy { f.service.confirmPayment(7L, f.request) }

        // then
        thrown.hasMessage("Payment result is unknown. The order remains pending.")
        verify(exactly = 1) { f.toss.getPayment(any(), "synthetic-key") }
        confirmVerified(f.store)
        verify(exactly = 1) { f.compensations.saveAndFlush(capture(savedRecord)) }
        assertThat(savedRecord.captured.stage).isEqualTo(PaymentCompensationRecord.Stage.UNKNOWN_RESULT)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "400:REJECT_CARD_PAYMENT", "400:INVALID_CARD_NUMBER", "400:EXCEED_MAX_DAILY_PAYMENT_COUNT",
            "400:INVALID_STOPPED_CARD", "400:BELOW_MINIMUM_AMOUNT", "400:NOT_FOUND_PAYMENT_SESSION", "400:FDS_ERROR",
            "403:REJECT_CARD_PAYMENT",
        ],
    )
    fun `목록에 있는 고객 거절일 때__승인하면__조회 없이 주문을 취소한다`(input: String) {
        // given
        val f = Fixture()
        val parts = input.split(":")

        every { f.orders.getOrder("order-uuid") } answers {
            f.outside("order")
            f.pendingOrder
        }
        every { f.payments.findFirstByOrderIdAndStatusOrderByIdAsc("order-uuid", PaymentStatus.DONE) } returns
            Optional.empty()
        every { f.toss.confirmPayment(any(), any()) } answers {
            f.outside("provider")
            throw f.clientError(HttpStatus.valueOf(parts[0].toInt()), "{\"code\":\"" + parts[1] + "\"}")
        }
        every { f.store.failPayment(any()) } returns true

        // when
        val result = f.service.confirmPayment(7L, f.request)

        // then
        assertThat(result.status).isEqualTo("ABORTED")
        verify(exactly = 1) { f.store.failPayment("order-uuid") }
        verify(exactly = 0) { f.toss.getPayment(any(), any()) }
        confirmVerified(f.compensations)
    }

    @Test
    fun `거절 중에 동시 요청이 이미 결제했을 때__승인하면__기록된 DONE을 재생한다`() {
        // given
        val f = Fixture()

        every { f.orders.getOrder("order-uuid") } answers {
            f.outside("order")
            f.pendingOrder
        }
        every { f.payments.findFirstByOrderIdAndStatusOrderByIdAsc("order-uuid", PaymentStatus.DONE) } returnsMany
            listOf(Optional.empty(), Optional.of(f.existing("concurrent-key")))
        every { f.toss.confirmPayment(any(), any()) } answers {
            f.outside("provider")
            throw f.clientError(HttpStatus.FORBIDDEN, "{\"code\":\"REJECT_CARD_PAYMENT\"}")
        }
        every { f.store.failPayment(any()) } returns false

        // when
        val result = f.service.confirmPayment(7L, f.request)

        // then
        assertThat(result.status).isEqualTo("DONE")
        assertThat(result.paymentKey).isEqualTo("concurrent-key")
    }

    @Test
    fun `다른 주문에 대한 ABORTED 조회일 때__결과가 불명이면__이 주문을 절대 취소하지 않는다`() {
        // given
        val f = Fixture()

        every { f.orders.getOrder("order-uuid") } answers {
            f.outside("order")
            f.pendingOrder
        }
        every { f.payments.findFirstByOrderIdAndStatusOrderByIdAsc("order-uuid", PaymentStatus.DONE) } returns
            Optional.empty()
        every { f.toss.confirmPayment(any(), any()) } answers {
            f.outside("provider")
            throw ResourceAccessException("synthetic")
        }
        every { f.toss.getPayment(any(), any()) } returns
            TossPaymentResponse.builder().paymentKey("synthetic-key").orderId("other-order").status("ABORTED").build()
        every { f.compensations.saveAndFlush(any<PaymentCompensationRecord>()) } answers { firstArg() }

        // when
        val thrown = assertThatThrownBy { f.service.confirmPayment(7L, f.request) }

        // then
        thrown.hasMessage("Payment result is unknown. The order remains pending.")
        confirmVerified(f.store)
    }

    @Test
    fun `커밋 후 순위 반영이 실패할 때__결제가 반영되면__그래도 승인을 돌려준다`() {
        // given
        val f = Fixture()
        val response = f.done()

        every { f.orders.getOrder("order-uuid") } answers {
            f.outside("order")
            f.pendingOrder
        }
        every { f.payments.findFirstByOrderIdAndStatusOrderByIdAsc("order-uuid", PaymentStatus.DONE) } returns
            Optional.empty()
        every { f.toss.confirmPayment(any(), any()) } answers {
            f.outside("provider")
            response
        }
        every { f.store.completePayment(any(), any()) } returns
            StorePaymentEvents.Completed(Completion.APPLIED, Runnable { throw IllegalStateException("synthetic redis") })
        every { f.payments.saveAndFlush(any<Payment>()) } answers { firstArg() }

        // when
        val result = f.service.confirmPayment(7L, f.request)

        // then
        assertThat(result).isSameAs(response)
        verify(exactly = 0) { f.toss.cancelPayment(any(), any(), any()) }
        confirmVerified(f.compensations)
    }

    @Test
    fun `타임아웃 후 DONE 조회일 때__승인하면__조회된 결제를 반영한다`() {
        // given
        val f = Fixture()
        val looked = f.done()

        every { f.orders.getOrder("order-uuid") } answers {
            f.outside("order")
            f.pendingOrder
        }
        every { f.payments.findFirstByOrderIdAndStatusOrderByIdAsc("order-uuid", PaymentStatus.DONE) } answers {
            if (f.manager.writing) {
                f.events.add("done-check")
            }
            Optional.empty()
        }
        every { f.toss.confirmPayment(any(), any()) } answers {
            f.outside("provider")
            throw ResourceAccessException("synthetic read timeout")
        }
        every { f.toss.getPayment(any(), "synthetic-key") } answers {
            f.outside("lookup")
            looked
        }
        every { f.store.completePayment(any(), any()) } answers {
            f.events.add("complete:" + secondArg<Long>())
            StorePaymentEvents.Completed(Completion.APPLIED, Runnable { f.outside("rank") })
        }
        every { f.payments.saveAndFlush(any<Payment>()) } answers {
            f.events.add("payment")
            firstArg()
        }

        // when
        val result = f.service.confirmPayment(7L, f.request)

        // then
        assertThat(result).isSameAs(looked)
        assertThat(f.events).containsExactly(
            "order", "begin:ro", "commit", "provider", "lookup", "begin:rw", "complete:200", "done-check", "payment", "commit", "rank",
        )
        confirmVerified(f.compensations)
    }

    @Test
    fun `제공사 5xx 후 ABORTED 조회일 때__승인하면__명확한 실패로 취급한다`() {
        // given
        val f = Fixture()

        every { f.orders.getOrder("order-uuid") } answers {
            f.outside("order")
            f.pendingOrder
        }
        every { f.payments.findFirstByOrderIdAndStatusOrderByIdAsc("order-uuid", PaymentStatus.DONE) } returns
            Optional.empty()
        every { f.toss.confirmPayment(any(), any()) } answers {
            f.outside("provider")
            throw HttpServerErrorException.create(
                HttpStatus.BAD_GATEWAY, "Bad Gateway", HttpHeaders.EMPTY, ByteArray(0), StandardCharsets.UTF_8,
            )
        }
        every { f.toss.getPayment(any(), "synthetic-key") } returns
            TossPaymentResponse.builder().paymentKey("synthetic-key").orderId("order-uuid").status("ABORTED").build()
        every { f.store.failPayment(any()) } returns true

        // when
        val result = f.service.confirmPayment(7L, f.request)

        // then
        assertThat(result.status).isEqualTo("ABORTED")
        verify(exactly = 1) { f.store.failPayment("order-uuid") }
        verify(exactly = 0) { f.store.completePayment(any(), any()) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["lookup-error", "lookup-in-progress", "lookup-other-order"])
    fun `알 수 없는 제공사 결과일 때__조회로도 해소되지 않으면__주문을 대기로 두고 보상을 기록한다`(
        kind: String,
        output: CapturedOutput,
    ) {
        // given
        val f = Fixture()
        val savedRecord = slot<PaymentCompensationRecord>()

        every { f.orders.getOrder("order-uuid") } answers {
            f.outside("order")
            f.pendingOrder
        }
        every { f.payments.findFirstByOrderIdAndStatusOrderByIdAsc("order-uuid", PaymentStatus.DONE) } returns
            Optional.empty()
        every { f.toss.confirmPayment(any(), any()) } answers {
            f.outside("provider")
            throw ResourceAccessException("synthetic connect failure")
        }
        if (kind == "lookup-error") {
            every { f.toss.getPayment(any(), any()) } throws ResourceAccessException("synthetic")
        } else {
            every { f.toss.getPayment(any(), any()) } returns
                TossPaymentResponse.builder().paymentKey("synthetic-key")
                    .orderId(if (kind == "lookup-other-order") "other-order" else "order-uuid")
                    .status(if (kind == "lookup-other-order") "DONE" else "IN_PROGRESS").build()
        }
        every { f.compensations.saveAndFlush(any<PaymentCompensationRecord>()) } answers {
            val record = firstArg<PaymentCompensationRecord>()
            ReflectionTestUtils.setField(record, "id", 41L)
            record
        }

        // when
        val thrown = assertThatThrownBy { f.service.confirmPayment(7L, f.request) }

        // then
        thrown.isInstanceOf(RuntimeException::class.java)
            .hasMessage("Payment result is unknown. The order remains pending.")
        confirmVerified(f.store)
        verify(exactly = 0) { f.toss.cancelPayment(any(), any(), any()) }
        verify(exactly = 1) { f.compensations.saveAndFlush(capture(savedRecord)) }
        val record = savedRecord.captured
        assertThat(record.stage).isEqualTo(PaymentCompensationRecord.Stage.UNKNOWN_RESULT)
        assertThat(record.reason).isEqualTo("PROVIDER_RESULT_UNKNOWN")
        assertThat(record.paymentKey).isEqualTo("synthetic-key")
        assertThat(record.orderId).isEqualTo("order-uuid")
        assertThat(record.userId).isEqualTo(7L)
        assertThat(record.amount).isEqualTo(200L)
        assertThat(record.createdAt).isEqualTo(LocalDateTime.of(2026, 10, 22, 10, 0))
        assertThat(record.resolvedAt).isNull()
        assertThat(output.all)
            .contains(
                "PAYMENT_COMPENSATION_REQUIRED paymentKey=synthetic-key orderId=order-uuid amount=200 userId=7 stage=UNKNOWN_RESULT reason=PROVIDER_RESULT_UNKNOWN recordId=41",
            )
            .doesNotContain("synthetic-secret")
            .doesNotContain("c3ludGhldGljLXNlY3JldDo=")
    }

    @Test
    fun `보상 기록을 저장할 수 없을 때__보상이 필요하면__기록 ID 없이 고정 로그 줄을 남긴다`(
        output: CapturedOutput,
    ) {
        // given
        val f = Fixture()

        every { f.orders.getOrder("order-uuid") } answers {
            f.outside("order")
            f.pendingOrder
        }
        every { f.payments.findFirstByOrderIdAndStatusOrderByIdAsc("order-uuid", PaymentStatus.DONE) } returns
            Optional.empty()
        every { f.toss.confirmPayment(any(), any()) } answers {
            f.outside("provider")
            throw ResourceAccessException("synthetic")
        }
        every { f.toss.getPayment(any(), any()) } throws ResourceAccessException("synthetic")
        every { f.compensations.saveAndFlush(any<PaymentCompensationRecord>()) } throws
            DataIntegrityViolationException("synthetic")

        // when
        val thrown = assertThatThrownBy { f.service.confirmPayment(7L, f.request) }

        // then
        thrown.hasMessage("Payment result is unknown. The order remains pending.")
        assertThat(output.all).contains(
            "PAYMENT_COMPENSATION_REQUIRED paymentKey=synthetic-key orderId=order-uuid amount=200 userId=7 stage=UNKNOWN_RESULT reason=PROVIDER_RESULT_UNKNOWN recordId=none",
        )
    }

    @Test
    fun `잠긴 주문이 더 이상 결제 가능하지 않을 때__반영하면__롤백하고 제공사 결제를 취소한다`() {
        // given
        val f = Fixture()

        every { f.orders.getOrder("order-uuid") } answers {
            f.outside("order")
            f.pendingOrder
        }
        every { f.payments.findFirstByOrderIdAndStatusOrderByIdAsc("order-uuid", PaymentStatus.DONE) } answers {
            if (f.manager.writing) {
                f.events.add("done-check")
            }
            Optional.empty()
        }
        every { f.payments.findByPaymentKey(any()) } answers {
            f.events.add("key-check")
            Optional.empty()
        }
        every { f.toss.confirmPayment(any(), any()) } answers {
            f.outside("provider")
            f.done()
        }
        every { f.store.completePayment(any(), any()) } answers {
            f.events.add("complete:" + secondArg<Long>())
            StorePaymentEvents.Completed(Completion.REJECTED, Runnable { f.outside("rank") })
        }
        every { f.toss.cancelPayment(any(), any(), any()) } answers {
            f.outside("cancel")
            null
        }

        // when
        val thrown = assertThatThrownBy { f.service.confirmPayment(7L, f.request) }

        // then
        thrown.hasMessage("Payment processed but failed to save record. Payment Cancelled.")
        assertThat(f.events).containsExactly(
            "order", "begin:ro", "commit", "provider", "begin:rw", "complete:200", "done-check", "rollback",
            "begin:ro", "key-check", "commit", "cancel",
        )
        verify(exactly = 0) { f.payments.saveAndFlush(any<Payment>()) }
        confirmVerified(f.compensations)
    }

    @Test
    fun `같은 키가 이미 기록되어 있을 때__다시 반영하면__취소 없이 멱등하게 성공한다`() {
        // given
        val f = Fixture()
        val response = f.done()

        every { f.orders.getOrder("order-uuid") } answers {
            f.outside("order")
            f.pendingOrder
        }
        every { f.payments.findFirstByOrderIdAndStatusOrderByIdAsc("order-uuid", PaymentStatus.DONE) } returnsMany
            listOf(Optional.empty(), Optional.of(f.existing("synthetic-key")))
        every { f.toss.confirmPayment(any(), any()) } answers {
            f.outside("provider")
            response
        }
        every { f.store.completePayment(any(), any()) } returns
            StorePaymentEvents.Completed(Completion.ALREADY_PAID, Runnable { f.outside("rank") })

        // when
        val result = f.service.confirmPayment(7L, f.request)

        // then
        assertThat(result).isSameAs(response)
        assertThat(f.events.last()).isEqualTo("commit")
        verify(exactly = 0) { f.payments.saveAndFlush(any<Payment>()) }
        verify(exactly = 0) { f.toss.cancelPayment(any(), any(), any()) }
    }

    @Test
    fun `다른 키가 이미 주문을 결제했을 때__반영하면__롤백하고 이 결제를 취소한다`() {
        // given
        val f = Fixture()

        every { f.orders.getOrder("order-uuid") } answers {
            f.outside("order")
            f.pendingOrder
        }
        every { f.payments.findFirstByOrderIdAndStatusOrderByIdAsc("order-uuid", PaymentStatus.DONE) } returnsMany
            listOf(Optional.empty(), Optional.of(f.existing("other-key")))
        every { f.payments.findByPaymentKey(any()) } returns Optional.empty()
        every { f.toss.confirmPayment(any(), any()) } answers {
            f.outside("provider")
            f.done()
        }
        every { f.store.completePayment(any(), any()) } returns
            StorePaymentEvents.Completed(Completion.ALREADY_PAID, Runnable { f.outside("rank") })
        every { f.toss.cancelPayment(any(), any(), any()) } returns null

        // when
        val thrown = assertThatThrownBy { f.service.confirmPayment(7L, f.request) }

        // then
        thrown.hasMessage("Payment processed but failed to save record. Payment Cancelled.")
        verify(exactly = 1) { f.toss.cancelPayment(any(), "synthetic-key", any()) }
        verify(exactly = 0) { f.payments.saveAndFlush(any<Payment>()) }
    }

    @Test
    fun `삽입이 실패했지만 같은 키가 DONE일 때__보상하면__취소하지 않고 저장된 결제를 돌려준다`() {
        // given
        val f = Fixture()

        every { f.orders.getOrder("order-uuid") } answers {
            f.outside("order")
            f.pendingOrder
        }
        every { f.payments.findFirstByOrderIdAndStatusOrderByIdAsc("order-uuid", PaymentStatus.DONE) } returns
            Optional.empty()
        every { f.toss.confirmPayment(any(), any()) } answers {
            f.outside("provider")
            f.done()
        }
        every { f.store.completePayment(any(), any()) } returns
            StorePaymentEvents.Completed(Completion.APPLIED, Runnable { f.outside("rank") })
        every { f.payments.saveAndFlush(any<Payment>()) } throws DataIntegrityViolationException("synthetic unique violation")
        every { f.payments.findByPaymentKey("synthetic-key") } returns Optional.of(f.existing("synthetic-key"))

        // when
        val result = f.service.confirmPayment(7L, f.request)

        // then
        assertThat(result.status).isEqualTo("DONE")
        assertThat(result.paymentKey).isEqualTo("synthetic-key")
        verify(exactly = 0) { f.toss.cancelPayment(any(), any(), any()) }
        confirmVerified(f.compensations)
    }

    @Test
    fun `커밋과 취소가 모두 실패할 때__보상하면__CANCEL_FAILED를 기록한다`(
        output: CapturedOutput,
    ) {
        // given
        val f = Fixture()
        val savedRecord = slot<PaymentCompensationRecord>()
        f.manager.commitFailures = 1
        f.manager.commitFailureAfter = 1

        every { f.orders.getOrder("order-uuid") } answers {
            f.outside("order")
            f.pendingOrder
        }
        every { f.payments.findFirstByOrderIdAndStatusOrderByIdAsc("order-uuid", PaymentStatus.DONE) } returns
            Optional.empty()
        every { f.payments.findByPaymentKey(any()) } returns Optional.empty()
        every { f.payments.saveAndFlush(any<Payment>()) } answers { firstArg() }
        every { f.toss.confirmPayment(any(), any()) } answers {
            f.outside("provider")
            f.done()
        }
        every { f.store.completePayment(any(), any()) } returns
            StorePaymentEvents.Completed(Completion.APPLIED, Runnable { f.outside("rank") })
        every { f.toss.cancelPayment(any(), any(), any()) } throws ResourceAccessException("synthetic")
        every { f.compensations.saveAndFlush(any<PaymentCompensationRecord>()) } answers {
            val record = firstArg<PaymentCompensationRecord>()
            ReflectionTestUtils.setField(record, "id", 41L)
            record
        }

        // when
        val thrown = assertThatThrownBy { f.service.confirmPayment(7L, f.request) }

        // then
        thrown.hasMessage("Payment processed but failed to save record. Payment cancellation failed.")
        verify(exactly = 1) { f.compensations.saveAndFlush(capture(savedRecord)) }
        assertThat(savedRecord.captured.stage).isEqualTo(PaymentCompensationRecord.Stage.CANCEL_FAILED)
        assertThat(savedRecord.captured.reason).isEqualTo("PERSIST_FAILED")
        assertThat(output.all).contains(
            "PAYMENT_COMPENSATION_REQUIRED paymentKey=synthetic-key orderId=order-uuid amount=200 userId=7 stage=CANCEL_FAILED reason=PERSIST_FAILED recordId=41",
        )
    }

    @Test
    fun `쓰기 연결이 두 번 실패할 때__반영하면__짧은 대기 후 재시도해 성공한다`() {
        // given
        val f = Fixture()
        f.manager.writerBeginFailures = 2

        every { f.orders.getOrder("order-uuid") } answers {
            f.outside("order")
            f.pendingOrder
        }
        every { f.payments.findFirstByOrderIdAndStatusOrderByIdAsc("order-uuid", PaymentStatus.DONE) } returns
            Optional.empty()
        every { f.toss.confirmPayment(any(), any()) } answers {
            f.outside("provider")
            f.done()
        }
        every { f.store.completePayment(any(), any()) } returns
            StorePaymentEvents.Completed(Completion.APPLIED, Runnable { f.outside("rank") })
        every { f.payments.saveAndFlush(any<Payment>()) } answers { firstArg() }

        // when
        val result = f.service.confirmPayment(7L, f.request)

        // then
        assertThat(result.status).isEqualTo("DONE")
        assertThat(f.pauses).containsExactly(100L, 200L)
        verify(exactly = 1) { f.payments.saveAndFlush(any<Payment>()) }
        verify(exactly = 0) { f.toss.cancelPayment(any(), any(), any()) }
    }

    @Test
    fun `승인 후 연결이 전혀 없을 때__반영하면__확인되지 않은 결제를 취소하지 않고 기록 없이 로그만 남긴다`(
        output: CapturedOutput,
    ) {
        // given
        val f = Fixture()

        every { f.orders.getOrder("order-uuid") } answers {
            f.outside("order")
            f.pendingOrder
        }
        every { f.payments.findFirstByOrderIdAndStatusOrderByIdAsc("order-uuid", PaymentStatus.DONE) } returns
            Optional.empty()
        every { f.toss.confirmPayment(any(), any()) } answers {
            f.manager.writerBeginFailures = 100
            f.manager.readerBeginFailures = 100
            f.outside("provider")
            f.done()
        }

        // when
        val thrown = assertThatThrownBy { f.service.confirmPayment(7L, f.request) }

        // then
        thrown.hasMessage("Payment processed but its record could not be verified.")
        verify(exactly = 0) { f.toss.cancelPayment(any(), any(), any()) }
        confirmVerified(f.store)
        assertThat(output.all).contains("stage=UNKNOWN_RESULT reason=WRITER_UNAVAILABLE_UNVERIFIED recordId=none")
    }

    @Test
    fun `다른 주문에 대한 승인일 때__승인하면__어느 주문도 건드리지 않고 취소한다`() {
        // given
        val f = Fixture()
        val other = TossPaymentResponse.builder().paymentKey("synthetic-key").orderId("other-order")
            .totalAmount(200L).status("DONE").build()

        every { f.orders.getOrder("order-uuid") } answers {
            f.outside("order")
            f.pendingOrder
        }
        every { f.payments.findFirstByOrderIdAndStatusOrderByIdAsc("order-uuid", PaymentStatus.DONE) } returns
            Optional.empty()
        every { f.payments.findByPaymentKey(any()) } returns Optional.empty()
        every { f.toss.confirmPayment(any(), any()) } answers {
            f.outside("provider")
            other
        }
        every { f.toss.cancelPayment(any(), any(), any()) } returns null

        // when
        val thrown = assertThatThrownBy { f.service.confirmPayment(7L, f.request) }

        // then
        thrown.hasMessage("Payment processed but failed to save record. Payment Cancelled.")
        confirmVerified(f.store)
        verify(exactly = 1) { f.toss.cancelPayment(any(), "synthetic-key", any()) }
    }

    @Test
    fun `DONE이 아닌 제공사 상태일 때__승인하면__쓰기 없이 그대로 돌려준다`() {
        // given
        val f = Fixture()
        val waiting = TossPaymentResponse.builder().paymentKey("synthetic-key").orderId("order-uuid")
            .status("WAITING_FOR_DEPOSIT").build()

        every { f.orders.getOrder("order-uuid") } answers {
            f.outside("order")
            f.pendingOrder
        }
        every { f.payments.findFirstByOrderIdAndStatusOrderByIdAsc("order-uuid", PaymentStatus.DONE) } returns
            Optional.empty()
        every { f.toss.confirmPayment(any(), any()) } answers {
            f.outside("provider")
            waiting
        }

        // when
        val result = f.service.confirmPayment(7L, f.request)

        // then
        assertThat(result).isSameAs(waiting)
        confirmVerified(f.store, f.compensations)
        verify(exactly = 0) { f.payments.saveAndFlush(any<Payment>()) }
    }

    @Test
    fun `클라이언트가 보낸 식별자일 때__로그에 남기면__하나의 길이 제한된 토큰으로 줄인다`() {
        // given
        val longKey = "a".repeat(250)

        // when
        val sanitized = JpaPayments.safe("bad\nkey=1 x")
        val exactlyAtLimit = JpaPayments.safe("a".repeat(200))
        val truncated = JpaPayments.safe(longKey)
        val missing = JpaPayments.safe(null)

        // then
        assertThat(sanitized).isEqualTo("bad_key_1_x")
        assertThat(exactlyAtLimit).hasSize(200)
        assertThat(truncated).hasSize(200)
        assertThat(missing).isNull()
    }

    /** Mocks, the writer/reader manager and data builders; each test stubs exactly what its flow needs. */
    private class Fixture {
        val events = ArrayList<String>()
        val pauses = ArrayList<Long>()
        val payments = mockk<PaymentRepository>()
        val compensations = mockk<PaymentCompensationRecordRepository>()
        val orders = mockk<StorePaymentOrders>()
        val store = mockk<StorePaymentEvents>()
        val toss = mockk<TossPaymentsClient>()
        val manager = RecordingManager(events)
        val request: TossPaymentConfirmRequest =
            TossPaymentConfirmRequest.builder().paymentKey("synthetic-key").orderId("order-uuid").amount(200L).build()
        val pendingOrder = StorePaymentOrders.Order(8L, "order-uuid", 7L, 200L, "PENDING")
        val service: JpaPayments

        init {
            val writes = TransactionTemplate(manager)
            val reads = TransactionTemplate(manager)
            reads.isReadOnly = true
            service = JpaPayments(
                toss, payments, compensations, orders, store, "synthetic-secret", writes, reads,
                Clock.fixed(Instant.parse("2026-10-22T01:00:00Z"), ZoneId.of("Asia/Seoul")),
                JpaPayments.Pause { millis -> pauses.add(millis) },
            )
        }

        fun outside(event: String) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                .`as`("no transaction around $event")
                .isFalse()
            events.add(event)
        }

        fun clientError(status: HttpStatus, body: String): HttpClientErrorException =
            HttpClientErrorException.create(
                status, status.reasonPhrase, HttpHeaders.EMPTY,
                body.toByteArray(StandardCharsets.UTF_8), StandardCharsets.UTF_8,
            )

        fun done(): TossPaymentResponse =
            TossPaymentResponse.builder().paymentKey("synthetic-key").orderId("order-uuid").mId("synthetic-merchant")
                .currency("KRW").method("카드").totalAmount(200L).balanceAmount(200L).status("DONE")
                .requestedAt(OffsetDateTime.parse("2026-10-21T01:00:00+09:00"))
                .approvedAt(OffsetDateTime.parse("2026-10-21T01:00:01+09:00")).build()

        fun existing(key: String): Payment {
            val result = Payment.builder().paymentKey(key).orderId("order-uuid").userId(7L).amount(200L).method("카드")
                .requestedAt(done().requestedAt!!.toLocalDateTime()).build()
            result.approve(done().approvedAt!!.toLocalDateTime())
            return result
        }
    }

    private class RecordingManager(private val events: MutableList<String>) : AbstractPlatformTransactionManager() {
        var writerBeginFailures = 0
        var readerBeginFailures = 0
        var commitFailures = 0
        var commitFailureAfter = 0
        var commits = 0
        var writing = false

        override fun doGetTransaction(): Any = Any()

        override fun doBegin(transaction: Any, definition: TransactionDefinition) {
            if (definition.isReadOnly && readerBeginFailures > 0) {
                readerBeginFailures--
                throw CannotCreateTransactionException("synthetic pool timeout")
            }
            if (!definition.isReadOnly && writerBeginFailures > 0) {
                writerBeginFailures--
                throw CannotCreateTransactionException("synthetic pool timeout")
            }
            writing = !definition.isReadOnly
            events.add(if (definition.isReadOnly) "begin:ro" else "begin:rw")
        }

        override fun doCommit(status: DefaultTransactionStatus) {
            writing = false
            events.add("commit")
            commits++
            if (commitFailures > 0 && commits > commitFailureAfter) {
                commitFailures--
                throw TransactionSystemException("synthetic commit failure")
            }
        }

        override fun doRollback(status: DefaultTransactionStatus) {
            writing = false
            events.add("rollback")
        }
    }
}
