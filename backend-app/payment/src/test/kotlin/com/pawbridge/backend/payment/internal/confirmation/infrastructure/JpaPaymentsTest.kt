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
import com.pawbridge.backend.payment.support.anyNonNull
import com.pawbridge.backend.payment.support.eqNonNull
import com.pawbridge.backend.payment.support.sameNonNull
import com.pawbridge.backend.store.api.StorePaymentEvents
import com.pawbridge.backend.store.api.StorePaymentEvents.Completion
import com.pawbridge.backend.store.api.StorePaymentOrders
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when` as whenever
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
    fun given__an_owned_pending_order__when__the_provider_approves__then__call_it_outside_transactions_and_apply_payment_and_order_in_one_writer() {
        val f = Fixture()
        val response = f.done()
        f.provider(response)

        val result = f.service.confirmPayment(7L, f.request)

        assertThat(result).isSameAs(response)
        assertThat(f.events).containsExactly(
            "order", "begin:ro", "commit", "provider", "begin:rw", "complete:200", "done-check", "payment", "commit", "rank",
        )
        val payment = f.savedPayment!!
        assertThat(payment.userId).isEqualTo(7L)
        assertThat(payment.orderId).isEqualTo("order-uuid")
        assertThat(payment.paymentKey).isEqualTo("synthetic-key")
        assertThat(payment.status).isEqualTo(PaymentStatus.DONE)
        assertThat(payment.amount).isEqualTo(200L)
        assertThat(payment.requestedAt).isEqualTo(response.requestedAt!!.toLocalDateTime())
        assertThat(payment.approvedAt).isEqualTo(response.approvedAt!!.toLocalDateTime())
        verify(f.toss).confirmPayment(eqNonNull("Basic c3ludGhldGljLXNlY3JldDo="), sameNonNull(f.request))
        verifyNoMoreInteractions(f.toss)
        verifyNoInteractions(f.compensations)
        verify(f.store, never()).failPayment(anyString())
    }

    @Test
    fun given__an_existing_done_payment__when__the_owner_repeats_the_order_and_amount__then__return_the_stored_subset_without_provider() {
        val f = Fixture()
        val existing = f.existing("stored-synthetic-key")
        f.order(StorePaymentOrders.Order(8L, "order-uuid", 7L, 200L, "PAID"))
        whenever(f.payments.findFirstByOrderIdAndStatusOrderByIdAsc("order-uuid", PaymentStatus.DONE))
            .thenReturn(Optional.of(existing))

        val result = f.service.confirmPayment(7L, f.request)

        assertThat(result.status).isEqualTo("DONE")
        assertThat(result.paymentKey).isEqualTo("stored-synthetic-key")
        assertThat(result.totalAmount).isEqualTo(200L)
        assertThat(result.requestedAt).isEqualTo(existing.requestedAt!!.atOffset(ZoneOffset.ofHours(9)))
        assertThat(result.approvedAt).isEqualTo(existing.approvedAt!!.atOffset(ZoneOffset.ofHours(9)))
        assertThat(f.events).containsExactly("order", "begin:ro", "commit")
        verifyNoInteractions(f.toss, f.store)
    }

    @Test
    fun given__another_members_already_paid_order__when__confirming__then__reject_403_before_revealing_the_stored_payment() {
        val f = Fixture()
        f.order(StorePaymentOrders.Order(8L, "order-uuid", 7L, 200L, "PAID"))
        whenever(f.payments.findFirstByOrderIdAndStatusOrderByIdAsc("order-uuid", PaymentStatus.DONE))
            .thenReturn(Optional.of(f.existing("stored-synthetic-key")))

        assertThatThrownBy { f.service.confirmPayment(8L, f.request) }
            .isInstanceOf(PaymentOrderAccessDeniedException::class.java)
            .hasMessageNotContaining("stored-synthetic-key")
        assertThat(f.events).containsExactly("order")
        verify(f.payments, never()).findFirstByOrderIdAndStatusOrderByIdAsc(anyString(), any())
        verifyNoInteractions(f.toss, f.store)
    }

    @Test
    fun given__an_existing_done_payment_with_a_different_amount__when__repeating__then__keep_the_original_mismatch_failure() {
        val f = Fixture()
        val existing = f.existing("stored-synthetic-key")
        whenever(f.payments.findFirstByOrderIdAndStatusOrderByIdAsc("order-uuid", PaymentStatus.DONE))
            .thenReturn(Optional.of(existing))
        val request = TossPaymentConfirmRequest.builder().paymentKey("synthetic-key").orderId("order-uuid").amount(201L).build()

        assertThatThrownBy { f.service.confirmPayment(7L, request) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Payment exists but amount mismatch")
        verifyNoInteractions(f.toss, f.store)
    }

    @Test
    fun given__another_members_order__when__confirming__then__reject_before_the_provider() {
        val f = Fixture()

        assertThatThrownBy { f.service.confirmPayment(8L, f.request) }
            .isInstanceOf(PaymentOrderAccessDeniedException::class.java)
        verifyNoInteractions(f.toss, f.store)
    }

    @ParameterizedTest
    @ValueSource(strings = ["PAID", "CANCELLED", "COMPLETED", "FAILED"])
    fun given__a_non_pending_order__when__confirming__then__reject_before_the_provider(status: String) {
        val f = Fixture()
        f.order(StorePaymentOrders.Order(8L, "order-uuid", 7L, 200L, status))

        assertThatThrownBy { f.service.confirmPayment(7L, f.request) }
            .isInstanceOf(PaymentOrderNotPayableException::class.java)
        verifyNoInteractions(f.toss, f.store)
    }

    @Test
    fun given__a_different_order_amount__when__confirming__then__keep_the_original_mismatch_failure_before_the_provider() {
        val f = Fixture()
        f.order(StorePaymentOrders.Order(8L, "order-uuid", 7L, 300L, "PENDING"))

        assertThatThrownBy { f.service.confirmPayment(7L, f.request) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Payment Amount Mismatch")
        verifyNoInteractions(f.toss, f.store)
    }

    @Test
    fun given__a_clear_provider_rejection__when__confirming__then__cancel_the_pending_order_in_one_writer_and_return_aborted() {
        val f = Fixture()
        f.providerFails(
            HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST, "Bad Request", HttpHeaders.EMPTY,
                "{\"code\":\"REJECT_CARD_PAYMENT\"}".toByteArray(StandardCharsets.UTF_8), StandardCharsets.UTF_8,
            ),
        )

        val result = f.service.confirmPayment(7L, f.request)

        assertThat(result.status).isEqualTo("ABORTED")
        assertThat(result.orderId).isEqualTo("order-uuid")
        assertThat(result.paymentKey).isEqualTo("synthetic-key")
        assertThat(f.events).containsExactly("order", "begin:ro", "commit", "provider", "begin:rw", "fail", "commit")
        verify(f.toss, never()).getPayment(anyString(), anyString())
        verify(f.toss, never()).cancelPayment(anyString(), anyString(), anyNonNull())
        verify(f.store, never()).completePayment(anyString(), anyLong())
        verifyNoInteractions(f.compensations)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "401:UNAUTHORIZED_KEY", "429:", "403:FORBIDDEN_REQUEST", "400:INVALID_API_KEY",
            "400:NOT_FOUND_TERMINAL_ID", "403:INCORRECT_BASIC_AUTH_FORMAT",
        ],
    )
    fun given__toss_did_not_process_our_request__when__confirming__then__keep_the_order_pending_without_cancel_or_record(
        input: String,
    ) {
        val f = Fixture()
        val parts = input.split(":")
        val body = if (parts[1].isEmpty()) "" else "{\"code\":\"" + parts[1] + "\",\"message\":\"synthetic\"}"
        f.providerFails(
            HttpClientErrorException.create(
                HttpStatus.valueOf(parts[0].toInt()), "synthetic", HttpHeaders.EMPTY,
                body.toByteArray(StandardCharsets.UTF_8), StandardCharsets.UTF_8,
            ),
        )

        assertThatThrownBy { f.service.confirmPayment(7L, f.request) }
            .hasMessage("Payment provider did not process the request. The order remains pending.")
        assertThat(f.events).containsExactly("order", "begin:ro", "commit", "provider")
        verifyNoInteractions(f.store, f.compensations)
        verify(f.toss, never()).getPayment(anyString(), anyString())
        verify(f.toss, never()).cancelPayment(anyString(), anyString(), anyNonNull())
    }

    @Test
    fun given__a_403_card_decline__when__confirming__then__treat_it_as_a_business_rejection() {
        val f = Fixture()
        f.providerFails(
            HttpClientErrorException.create(
                HttpStatus.FORBIDDEN, "Forbidden", HttpHeaders.EMPTY,
                "{\"code\":\"REJECT_CARD_PAYMENT\",\"message\":\"synthetic\"}".toByteArray(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8,
            ),
        )

        assertThat(f.service.confirmPayment(7L, f.request).status).isEqualTo("ABORTED")
        verify(f.store).failPayment("order-uuid")
        verifyNoInteractions(f.compensations)
    }

    @Test
    fun given__already_processed_and_a_recorded_payment__when__confirming__then__return_the_stored_done_from_a_short_read() {
        val f = Fixture()
        val existing = f.existing("synthetic-key")
        whenever(f.payments.findFirstByOrderIdAndStatusOrderByIdAsc("order-uuid", PaymentStatus.DONE))
            .thenReturn(Optional.empty()).thenReturn(Optional.of(existing))
        f.providerFails(
            HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST, "Bad Request", HttpHeaders.EMPTY,
                "{\"code\":\"ALREADY_PROCESSED_PAYMENT\"}".toByteArray(StandardCharsets.UTF_8), StandardCharsets.UTF_8,
            ),
        )

        val result = f.service.confirmPayment(7L, f.request)

        assertThat(result.status).isEqualTo("DONE")
        assertThat(result.paymentKey).isEqualTo("synthetic-key")
        assertThat(f.events).containsExactly("order", "begin:ro", "commit", "provider", "begin:ro", "commit")
        verifyNoInteractions(f.store, f.compensations)
    }

    @Test
    fun given__already_processed_without_a_recorded_payment__when__the_lookup_is_done__then__apply_it_in_the_writer() {
        val f = Fixture()
        val looked = f.done()
        f.providerFails(IllegalStateException("S008 synthetic"))
        whenever(f.toss.getPayment(anyString(), eqNonNull("synthetic-key"))).thenAnswer {
            f.outside("lookup")
            looked
        }

        assertThat(f.service.confirmPayment(7L, f.request)).isSameAs(looked)
        assertThat(f.events).containsExactly(
            "order", "begin:ro", "commit", "provider", "begin:ro", "commit", "lookup",
            "begin:rw", "complete:200", "done-check", "payment", "commit", "rank",
        )
        verifyNoInteractions(f.compensations)
    }

    @ParameterizedTest
    @ValueSource(strings = ["none", "ABORTED"])
    fun given__already_processed_without_a_recorded_payment__when__the_lookup_is_not_done__then__record_and_never_cancel_the_order(
        looked: String,
        output: CapturedOutput,
    ) {
        val f = Fixture()
        f.providerFails(
            HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST, "Bad Request", HttpHeaders.EMPTY,
                "{\"code\":\"ALREADY_PROCESSED_PAYMENT\"}".toByteArray(StandardCharsets.UTF_8), StandardCharsets.UTF_8,
            ),
        )
        if (looked != "none") {
            whenever(f.toss.getPayment(anyString(), anyString())).thenReturn(
                TossPaymentResponse.builder().paymentKey("synthetic-key").orderId("order-uuid").status(looked).build(),
            )
        }

        assertThatThrownBy { f.service.confirmPayment(7L, f.request) }
            .hasMessage("Payment result is unknown. The order remains pending.")
        verifyNoInteractions(f.store)
        verify(f.toss, never()).cancelPayment(anyString(), anyString(), anyNonNull())
        assertThat(f.savedRecord!!.reason).isEqualTo("ALREADY_PROCESSED_UNRECORDED")
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
    fun given__a_4xx_that_is_not_a_listed_rejection__when__confirming__then__resolve_by_lookup_and_keep_the_order_pending(
        input: String,
    ) {
        val f = Fixture()
        val parts = input.split(":")
        val body = if (parts[1].isEmpty()) "<html>gateway</html>" else "{\"code\":\"" + parts[1] + "\",\"message\":\"synthetic\"}"
        f.providerFails(
            HttpClientErrorException.create(
                HttpStatus.valueOf(parts[0].toInt()), "synthetic", HttpHeaders.EMPTY,
                body.toByteArray(StandardCharsets.UTF_8), StandardCharsets.UTF_8,
            ),
        )
        whenever(f.toss.getPayment(anyString(), eqNonNull("synthetic-key"))).thenReturn(
            TossPaymentResponse.builder().paymentKey("synthetic-key").orderId("order-uuid").status("IN_PROGRESS").build(),
        )

        assertThatThrownBy { f.service.confirmPayment(7L, f.request) }
            .hasMessage("Payment result is unknown. The order remains pending.")
        verify(f.toss).getPayment(anyString(), eqNonNull("synthetic-key"))
        verifyNoInteractions(f.store)
        assertThat(f.savedRecord!!.stage).isEqualTo(PaymentCompensationRecord.Stage.UNKNOWN_RESULT)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "REJECT_CARD_PAYMENT", "INVALID_CARD_NUMBER", "EXCEED_MAX_DAILY_PAYMENT_COUNT",
            "INVALID_STOPPED_CARD", "BELOW_MINIMUM_AMOUNT", "NOT_FOUND_PAYMENT_SESSION", "FDS_ERROR",
        ],
    )
    fun given__a_listed_customer_rejection__when__confirming__then__cancel_the_order_without_lookup(code: String) {
        val f = Fixture()
        f.providerFails(
            HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST, "Bad Request", HttpHeaders.EMPTY,
                ("{\"code\":\"" + code + "\"}").toByteArray(StandardCharsets.UTF_8), StandardCharsets.UTF_8,
            ),
        )

        assertThat(f.service.confirmPayment(7L, f.request).status).isEqualTo("ABORTED")
        verify(f.store).failPayment("order-uuid")
        verify(f.toss, never()).getPayment(anyString(), anyString())
    }

    @Test
    fun given__a_rejection_while_a_concurrent_request_already_paid__when__confirming__then__replay_the_recorded_done() {
        val f = Fixture()
        f.failCancels = false
        whenever(f.payments.findFirstByOrderIdAndStatusOrderByIdAsc("order-uuid", PaymentStatus.DONE))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(f.existing("concurrent-key")))
        f.providerFails(
            HttpClientErrorException.create(
                HttpStatus.FORBIDDEN, "Forbidden", HttpHeaders.EMPTY,
                "{\"code\":\"REJECT_CARD_PAYMENT\"}".toByteArray(StandardCharsets.UTF_8), StandardCharsets.UTF_8,
            ),
        )

        val result = f.service.confirmPayment(7L, f.request)

        assertThat(result.status).isEqualTo("DONE")
        assertThat(result.paymentKey).isEqualTo("concurrent-key")
    }

    @Test
    fun given__an_aborted_lookup_for_another_order__when__the_result_is_unknown__then__never_cancel_this_order() {
        val f = Fixture()
        f.providerFails(ResourceAccessException("synthetic"))
        whenever(f.toss.getPayment(anyString(), anyString())).thenReturn(
            TossPaymentResponse.builder().paymentKey("synthetic-key").orderId("other-order").status("ABORTED").build(),
        )

        assertThatThrownBy { f.service.confirmPayment(7L, f.request) }
            .hasMessage("Payment result is unknown. The order remains pending.")
        verifyNoInteractions(f.store)
    }

    @Test
    fun given__ranking_fails_after_commit__when__the_payment_is_applied__then__still_return_the_approval() {
        val f = Fixture()
        val response = f.done()
        f.provider(response)
        f.rankingFailure = IllegalStateException("synthetic redis")

        assertThat(f.service.confirmPayment(7L, f.request)).isSameAs(response)
        verify(f.toss, never()).cancelPayment(anyString(), anyString(), anyNonNull())
        verifyNoInteractions(f.compensations)
    }

    @Test
    fun given__a_timeout_and_a_done_lookup__when__confirming__then__apply_the_looked_up_payment() {
        val f = Fixture()
        val looked = f.done()
        f.providerFails(ResourceAccessException("synthetic read timeout"))
        whenever(f.toss.getPayment(anyString(), eqNonNull("synthetic-key"))).thenAnswer {
            f.outside("lookup")
            looked
        }

        val result = f.service.confirmPayment(7L, f.request)

        assertThat(result).isSameAs(looked)
        assertThat(f.events).containsExactly(
            "order", "begin:ro", "commit", "provider", "lookup", "begin:rw", "complete:200", "done-check", "payment", "commit", "rank",
        )
        verifyNoInteractions(f.compensations)
    }

    @Test
    fun given__a_provider_5xx_and_an_aborted_lookup__when__confirming__then__treat_it_as_a_clear_failure() {
        val f = Fixture()
        f.providerFails(
            HttpServerErrorException.create(
                HttpStatus.BAD_GATEWAY, "Bad Gateway", HttpHeaders.EMPTY, ByteArray(0), StandardCharsets.UTF_8,
            ),
        )
        whenever(f.toss.getPayment(anyString(), eqNonNull("synthetic-key"))).thenReturn(
            TossPaymentResponse.builder().paymentKey("synthetic-key").orderId("order-uuid").status("ABORTED").build(),
        )

        assertThat(f.service.confirmPayment(7L, f.request).status).isEqualTo("ABORTED")
        verify(f.store).failPayment("order-uuid")
        verify(f.store, never()).completePayment(anyString(), anyLong())
    }

    @ParameterizedTest
    @ValueSource(strings = ["lookup-error", "lookup-in-progress", "lookup-other-order"])
    fun given__an_unknown_provider_result__when__the_lookup_does_not_resolve_it__then__keep_the_order_pending_and_record_compensation(
        kind: String,
        output: CapturedOutput,
    ) {
        val f = Fixture()
        f.providerFails(ResourceAccessException("synthetic connect failure"))
        if (kind == "lookup-error") {
            whenever(f.toss.getPayment(anyString(), anyString())).thenThrow(ResourceAccessException("synthetic"))
        } else {
            whenever(f.toss.getPayment(anyString(), anyString())).thenReturn(
                TossPaymentResponse.builder().paymentKey("synthetic-key")
                    .orderId(if (kind == "lookup-other-order") "other-order" else "order-uuid")
                    .status(if (kind == "lookup-other-order") "DONE" else "IN_PROGRESS").build(),
            )
        }

        assertThatThrownBy { f.service.confirmPayment(7L, f.request) }
            .isInstanceOf(RuntimeException::class.java)
            .hasMessage("Payment result is unknown. The order remains pending.")
        verifyNoInteractions(f.store)
        verify(f.toss, never()).cancelPayment(anyString(), anyString(), anyNonNull())
        val record = f.savedRecord!!
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
    fun given__the_record_cannot_be_stored__when__compensation_is_required__then__still_log_the_fixed_line_with_no_record_id(
        output: CapturedOutput,
    ) {
        val f = Fixture()
        f.providerFails(ResourceAccessException("synthetic"))
        whenever(f.toss.getPayment(anyString(), anyString())).thenThrow(ResourceAccessException("synthetic"))
        doThrow(DataIntegrityViolationException("synthetic")).`when`(f.compensations)
            .saveAndFlush(anyNonNull<PaymentCompensationRecord>())

        assertThatThrownBy { f.service.confirmPayment(7L, f.request) }
            .hasMessage("Payment result is unknown. The order remains pending.")
        assertThat(output.all).contains(
            "PAYMENT_COMPENSATION_REQUIRED paymentKey=synthetic-key orderId=order-uuid amount=200 userId=7 stage=UNKNOWN_RESULT reason=PROVIDER_RESULT_UNKNOWN recordId=none",
        )
    }

    @Test
    fun given__the_locked_order_is_no_longer_payable__when__applying__then__roll_back_and_cancel_the_provider_payment() {
        val f = Fixture()
        f.provider(f.done())
        f.completion = Completion.REJECTED

        assertThatThrownBy { f.service.confirmPayment(7L, f.request) }
            .hasMessage("Payment processed but failed to save record. Payment Cancelled.")
        assertThat(f.events).containsExactly(
            "order", "begin:ro", "commit", "provider", "begin:rw", "complete:200", "done-check", "rollback",
            "begin:ro", "key-check", "commit", "cancel",
        )
        verify(f.payments, never()).saveAndFlush(anyNonNull<Payment>())
        verifyNoInteractions(f.compensations)
    }

    @Test
    fun given__the_same_key_is_already_recorded__when__applying_again__then__succeed_idempotently_without_cancel() {
        val f = Fixture()
        val response = f.done()
        f.provider(response)
        f.completion = Completion.ALREADY_PAID
        whenever(f.payments.findFirstByOrderIdAndStatusOrderByIdAsc("order-uuid", PaymentStatus.DONE))
            .thenReturn(Optional.empty())
            .thenAnswer {
                f.events.add("done-check")
                Optional.of(f.existing("synthetic-key"))
            }

        assertThat(f.service.confirmPayment(7L, f.request)).isSameAs(response)
        assertThat(f.events.last()).isEqualTo("commit")
        verify(f.payments, never()).saveAndFlush(anyNonNull<Payment>())
        verify(f.toss, never()).cancelPayment(anyString(), anyString(), anyNonNull())
    }

    @Test
    fun given__another_key_already_paid_the_order__when__applying__then__roll_back_and_cancel_this_payment() {
        val f = Fixture()
        f.provider(f.done())
        f.completion = Completion.ALREADY_PAID
        whenever(f.payments.findFirstByOrderIdAndStatusOrderByIdAsc("order-uuid", PaymentStatus.DONE))
            .thenReturn(Optional.empty())
            .thenAnswer {
                f.events.add("done-check")
                Optional.of(f.existing("other-key"))
            }

        assertThatThrownBy { f.service.confirmPayment(7L, f.request) }
            .hasMessage("Payment processed but failed to save record. Payment Cancelled.")
        verify(f.toss).cancelPayment(anyString(), eqNonNull("synthetic-key"), anyNonNull())
        verify(f.payments, never()).saveAndFlush(anyNonNull<Payment>())
    }

    @Test
    fun given__the_insert_fails_but_the_same_key_is_done__when__compensating__then__never_cancel_and_return_the_stored_payment() {
        val f = Fixture()
        f.provider(f.done())
        doThrow(DataIntegrityViolationException("synthetic unique violation")).`when`(f.payments)
            .saveAndFlush(anyNonNull<Payment>())
        whenever(f.payments.findByPaymentKey("synthetic-key")).thenReturn(Optional.of(f.existing("synthetic-key")))

        val result = f.service.confirmPayment(7L, f.request)

        assertThat(result.status).isEqualTo("DONE")
        assertThat(result.paymentKey).isEqualTo("synthetic-key")
        verify(f.toss, never()).cancelPayment(anyString(), anyString(), anyNonNull())
        verifyNoInteractions(f.compensations)
    }

    @Test
    fun given__the_commit_fails_and_the_cancel_fails__when__compensating__then__record_cancel_failed(
        output: CapturedOutput,
    ) {
        val f = Fixture()
        f.provider(f.done())
        f.manager.commitFailures = 1
        f.manager.commitFailureAfter = 1
        doThrow(ResourceAccessException("synthetic")).`when`(f.toss)
            .cancelPayment(anyString(), anyString(), anyNonNull())

        assertThatThrownBy { f.service.confirmPayment(7L, f.request) }
            .hasMessage("Payment processed but failed to save record. Payment cancellation failed.")
        assertThat(f.savedRecord!!.stage).isEqualTo(PaymentCompensationRecord.Stage.CANCEL_FAILED)
        assertThat(f.savedRecord!!.reason).isEqualTo("PERSIST_FAILED")
        assertThat(output.all).contains(
            "PAYMENT_COMPENSATION_REQUIRED paymentKey=synthetic-key orderId=order-uuid amount=200 userId=7 stage=CANCEL_FAILED reason=PERSIST_FAILED recordId=41",
        )
    }

    @Test
    fun given__writer_connections_fail_twice__when__applying__then__retry_with_short_pauses_and_succeed() {
        val f = Fixture()
        f.provider(f.done())
        f.manager.writerBeginFailures = 2

        assertThat(f.service.confirmPayment(7L, f.request).status).isEqualTo("DONE")
        assertThat(f.pauses).containsExactly(100L, 200L)
        verify(f.payments).saveAndFlush(anyNonNull<Payment>())
        verify(f.toss, never()).cancelPayment(anyString(), anyString(), anyNonNull())
    }

    @Test
    fun given__no_connection_at_all_after_approval__when__applying__then__never_cancel_unverified_and_log_without_record(
        output: CapturedOutput,
    ) {
        val f = Fixture()
        f.provider(f.done())
        doAnswer {
            f.manager.writerBeginFailures = 100
            f.manager.readerBeginFailures = 100
            f.outside("provider")
            f.done()
        }.`when`(f.toss).confirmPayment(anyString(), anyNonNull())

        assertThatThrownBy { f.service.confirmPayment(7L, f.request) }
            .hasMessage("Payment processed but its record could not be verified.")
        verify(f.toss, never()).cancelPayment(anyString(), anyString(), anyNonNull())
        verifyNoInteractions(f.store)
        assertThat(output.all).contains("stage=UNKNOWN_RESULT reason=WRITER_UNAVAILABLE_UNVERIFIED recordId=none")
    }

    @Test
    fun given__an_approval_for_another_order__when__confirming__then__never_touch_either_order_and_cancel_it() {
        val f = Fixture()
        val other = TossPaymentResponse.builder().paymentKey("synthetic-key").orderId("other-order")
            .totalAmount(200L).status("DONE").build()
        f.provider(other)

        assertThatThrownBy { f.service.confirmPayment(7L, f.request) }
            .hasMessage("Payment processed but failed to save record. Payment Cancelled.")
        verifyNoInteractions(f.store)
        verify(f.toss).cancelPayment(anyString(), eqNonNull("synthetic-key"), anyNonNull())
    }

    @Test
    fun given__a_non_done_provider_status__when__confirming__then__return_it_unchanged_without_writes() {
        val f = Fixture()
        val waiting = TossPaymentResponse.builder().paymentKey("synthetic-key").orderId("order-uuid")
            .status("WAITING_FOR_DEPOSIT").build()
        f.provider(waiting)

        assertThat(f.service.confirmPayment(7L, f.request)).isSameAs(waiting)
        verifyNoInteractions(f.store, f.compensations)
        verify(f.payments, never()).saveAndFlush(anyNonNull<Payment>())
    }

    @Test
    fun given__client_supplied_identifiers__when__logging__then__reduce_them_to_one_bounded_token() {
        assertThat(JpaPayments.safe("bad\nkey=1 x")).isEqualTo("bad_key_1_x")
        assertThat(JpaPayments.safe("a".repeat(200))).hasSize(200)
        assertThat(JpaPayments.safe("a".repeat(250))).hasSize(200)
        assertThat(JpaPayments.safe(null)).isNull()
    }

    private class Fixture {
        val events = ArrayList<String>()
        val pauses = ArrayList<Long>()
        val payments: PaymentRepository = mock(PaymentRepository::class.java)
        val compensations: PaymentCompensationRecordRepository = mock(PaymentCompensationRecordRepository::class.java)
        val orders: StorePaymentOrders = mock(StorePaymentOrders::class.java)
        val store: StorePaymentEvents = mock(StorePaymentEvents::class.java)
        val toss: TossPaymentsClient = mock(TossPaymentsClient::class.java)
        val manager = RecordingManager(events)
        val request: TossPaymentConfirmRequest =
            TossPaymentConfirmRequest.builder().paymentKey("synthetic-key").orderId("order-uuid").amount(200L).build()
        var completion = Completion.APPLIED
        var rankingFailure: RuntimeException? = null
        var failCancels = true
        var savedPayment: Payment? = null
        var savedRecord: PaymentCompensationRecord? = null
        val service: JpaPayments

        init {
            whenever(payments.findFirstByOrderIdAndStatusOrderByIdAsc(anyString(), any())).thenAnswer {
                if (manager.writing) {
                    events.add("done-check")
                }
                Optional.empty<Payment>()
            }
            whenever(payments.findByPaymentKey(anyString())).thenAnswer {
                events.add("key-check")
                Optional.empty<Payment>()
            }
            order(StorePaymentOrders.Order(8L, "order-uuid", 7L, 200L, "PENDING"))
            whenever(payments.saveAndFlush(anyNonNull<Payment>())).thenAnswer { call ->
                events.add("payment")
                savedPayment = call.getArgument(0)
                savedPayment
            }
            whenever(compensations.saveAndFlush(anyNonNull<PaymentCompensationRecord>())).thenAnswer { call ->
                val record = call.getArgument<PaymentCompensationRecord>(0)
                savedRecord = record
                ReflectionTestUtils.setField(record, "id", 41L)
                record
            }
            whenever(store.completePayment(anyString(), anyLong())).thenAnswer { call ->
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue()
                assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse()
                events.add("complete:" + call.getArgument<Long>(1))
                StorePaymentEvents.Completed(
                    completion,
                    Runnable {
                        if (rankingFailure != null) {
                            throw rankingFailure!!
                        }
                        outside("rank")
                    },
                )
            }
            whenever(store.failPayment(anyString())).thenAnswer {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue()
                events.add("fail")
                failCancels
            }
            whenever(toss.cancelPayment(anyString(), anyString(), anyNonNull())).thenAnswer {
                outside("cancel")
                null
            }
            val writes = TransactionTemplate(manager)
            val reads = TransactionTemplate(manager)
            reads.isReadOnly = true
            service = JpaPayments(
                toss, payments, compensations, orders, store, "synthetic-secret", writes, reads,
                Clock.fixed(Instant.parse("2026-10-22T01:00:00Z"), ZoneId.of("Asia/Seoul")),
                JpaPayments.Pause { millis -> pauses.add(millis) },
            )
        }

        fun order(value: StorePaymentOrders.Order) {
            doAnswer {
                outside("order")
                value
            }.`when`(orders).getOrder("order-uuid")
        }

        fun provider(response: TossPaymentResponse) {
            whenever(toss.confirmPayment(anyString(), anyNonNull())).thenAnswer {
                outside("provider")
                response
            }
        }

        fun providerFails(failure: RuntimeException) {
            whenever(toss.confirmPayment(anyString(), anyNonNull())).thenAnswer {
                outside("provider")
                throw failure
            }
        }

        fun outside(event: String) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                .`as`("no transaction around $event")
                .isFalse()
            events.add(event)
        }

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
