package com.pawbridge.backend.payment.internal.confirmation.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.pawbridge.backend.payment.internal.confirmation.application.PaymentOrderAccessDeniedException;
import com.pawbridge.backend.payment.internal.confirmation.application.PaymentOrderNotPayableException;
import com.pawbridge.backend.payment.internal.confirmation.application.dto.*;
import com.pawbridge.backend.payment.internal.confirmation.infrastructure.jpa.model.*;
import com.pawbridge.backend.payment.internal.confirmation.infrastructure.jpa.repository.*;
import com.pawbridge.backend.payment.internal.confirmation.infrastructure.toss.TossPaymentsClient;
import com.pawbridge.backend.store.api.StorePaymentEvents;
import com.pawbridge.backend.store.api.StorePaymentEvents.Completion;
import com.pawbridge.backend.store.api.StorePaymentOrders;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/** Direct confirmation flow with a stub provider and synthetic transactions; no database or network. */
@ExtendWith(OutputCaptureExtension.class)
class JpaPaymentsTest {
    @Test void given__an_owned_pending_order__when__the_provider_approves__then__call_it_outside_transactions_and_apply_payment_and_order_in_one_writer() {
        var f = new Fixture(); var response = f.done();
        f.provider(response);
        var result = f.service.confirmPayment(7L, f.request);
        assertThat(result).isSameAs(response);
        assertThat(f.events).containsExactly("order", "begin:ro", "commit", "provider", "begin:rw", "complete:200", "done-check", "payment", "commit", "rank");
        var payment = f.savedPayment;
        assertThat(payment.getUserId()).isEqualTo(7L);
        assertThat(payment.getOrderId()).isEqualTo("order-uuid");
        assertThat(payment.getPaymentKey()).isEqualTo("synthetic-key");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.DONE);
        assertThat(payment.getAmount()).isEqualTo(200L);
        assertThat(payment.getRequestedAt()).isEqualTo(response.getRequestedAt().toLocalDateTime());
        assertThat(payment.getApprovedAt()).isEqualTo(response.getApprovedAt().toLocalDateTime());
        verify(f.toss).confirmPayment(eq("Basic c3ludGhldGljLXNlY3JldDo="), same(f.request));
        verifyNoMoreInteractions(f.toss); verifyNoInteractions(f.compensations);
        verify(f.store, never()).failPayment(anyString());
    }

    @Test void given__an_existing_done_payment__when__the_owner_repeats_the_order_and_amount__then__return_the_stored_subset_without_provider() {
        var f = new Fixture(); var existing = f.existing("stored-synthetic-key");
        f.order(new StorePaymentOrders.Order(8L, "order-uuid", 7L, 200L, "PAID"));
        when(f.payments.findFirstByOrderIdAndStatusOrderByIdAsc("order-uuid", PaymentStatus.DONE)).thenReturn(Optional.of(existing));
        var result = f.service.confirmPayment(7L, f.request);
        assertThat(result.getStatus()).isEqualTo("DONE");
        assertThat(result.getPaymentKey()).isEqualTo("stored-synthetic-key");
        assertThat(result.getTotalAmount()).isEqualTo(200L);
        assertThat(result.getRequestedAt()).isEqualTo(existing.getRequestedAt().atOffset(ZoneOffset.ofHours(9)));
        assertThat(result.getApprovedAt()).isEqualTo(existing.getApprovedAt().atOffset(ZoneOffset.ofHours(9)));
        assertThat(f.events).containsExactly("order", "begin:ro", "commit");
        verifyNoInteractions(f.toss, f.store);
    }

    @Test void given__another_members_already_paid_order__when__confirming__then__reject_403_before_revealing_the_stored_payment() {
        var f = new Fixture(); f.order(new StorePaymentOrders.Order(8L, "order-uuid", 7L, 200L, "PAID"));
        when(f.payments.findFirstByOrderIdAndStatusOrderByIdAsc("order-uuid", PaymentStatus.DONE)).thenReturn(Optional.of(f.existing("stored-synthetic-key")));
        assertThatThrownBy(() -> f.service.confirmPayment(8L, f.request)).isInstanceOf(PaymentOrderAccessDeniedException.class)
                .hasMessageNotContaining("stored-synthetic-key");
        assertThat(f.events).containsExactly("order");
        verify(f.payments, never()).findFirstByOrderIdAndStatusOrderByIdAsc(anyString(), any());
        verifyNoInteractions(f.toss, f.store);
    }

    @Test void given__an_existing_done_payment_with_a_different_amount__when__repeating__then__keep_the_original_mismatch_failure() {
        var f = new Fixture(); var existing = f.existing("stored-synthetic-key");
        when(f.payments.findFirstByOrderIdAndStatusOrderByIdAsc("order-uuid", PaymentStatus.DONE)).thenReturn(Optional.of(existing));
        var request = TossPaymentConfirmRequest.builder().paymentKey("synthetic-key").orderId("order-uuid").amount(201L).build();
        assertThatThrownBy(() -> f.service.confirmPayment(7L, request)).isInstanceOf(IllegalStateException.class).hasMessage("Payment exists but amount mismatch");
        verifyNoInteractions(f.toss, f.store);
    }

    @Test void given__another_members_order__when__confirming__then__reject_before_the_provider() {
        var f = new Fixture();
        assertThatThrownBy(() -> f.service.confirmPayment(8L, f.request)).isInstanceOf(PaymentOrderAccessDeniedException.class);
        verifyNoInteractions(f.toss, f.store);
    }

    @ParameterizedTest @ValueSource(strings = {"PAID", "CANCELLED", "COMPLETED", "FAILED"})
    void given__a_non_pending_order__when__confirming__then__reject_before_the_provider(String status) {
        var f = new Fixture(); f.order(new StorePaymentOrders.Order(8L, "order-uuid", 7L, 200L, status));
        assertThatThrownBy(() -> f.service.confirmPayment(7L, f.request)).isInstanceOf(PaymentOrderNotPayableException.class);
        verifyNoInteractions(f.toss, f.store);
    }

    @Test void given__a_different_order_amount__when__confirming__then__keep_the_original_mismatch_failure_before_the_provider() {
        var f = new Fixture(); f.order(new StorePaymentOrders.Order(8L, "order-uuid", 7L, 300L, "PENDING"));
        assertThatThrownBy(() -> f.service.confirmPayment(7L, f.request)).isInstanceOf(IllegalStateException.class).hasMessage("Payment Amount Mismatch");
        verifyNoInteractions(f.toss, f.store);
    }

    @Test void given__a_clear_provider_rejection__when__confirming__then__cancel_the_pending_order_in_one_writer_and_return_aborted() {
        var f = new Fixture();
        f.providerFails(HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request", HttpHeaders.EMPTY,
                "{\"code\":\"REJECT_CARD_PAYMENT\"}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        var result = f.service.confirmPayment(7L, f.request);
        assertThat(result.getStatus()).isEqualTo("ABORTED");
        assertThat(result.getOrderId()).isEqualTo("order-uuid"); assertThat(result.getPaymentKey()).isEqualTo("synthetic-key");
        assertThat(f.events).containsExactly("order", "begin:ro", "commit", "provider", "begin:rw", "fail", "commit");
        verify(f.toss, never()).getPayment(anyString(), anyString()); verify(f.toss, never()).cancelPayment(anyString(), anyString(), any());
        verify(f.store, never()).completePayment(anyString(), anyLong()); verifyNoInteractions(f.compensations);
    }

    @ParameterizedTest @ValueSource(strings = {"401:UNAUTHORIZED_KEY", "429:", "403:FORBIDDEN_REQUEST", "400:INVALID_API_KEY",
            "400:NOT_FOUND_TERMINAL_ID", "403:INCORRECT_BASIC_AUTH_FORMAT"})
    void given__toss_did_not_process_our_request__when__confirming__then__keep_the_order_pending_without_cancel_or_record(String input) {
        var f = new Fixture(); var parts = input.split(":", -1);
        var body = parts[1].isEmpty() ? "" : "{\"code\":\"" + parts[1] + "\",\"message\":\"synthetic\"}";
        f.providerFails(HttpClientErrorException.create(HttpStatus.valueOf(Integer.parseInt(parts[0])), "synthetic", HttpHeaders.EMPTY,
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        assertThatThrownBy(() -> f.service.confirmPayment(7L, f.request))
                .hasMessage("Payment provider did not process the request. The order remains pending.");
        assertThat(f.events).containsExactly("order", "begin:ro", "commit", "provider");
        verifyNoInteractions(f.store, f.compensations);
        verify(f.toss, never()).getPayment(anyString(), anyString()); verify(f.toss, never()).cancelPayment(anyString(), anyString(), any());
    }

    @Test void given__a_403_card_decline__when__confirming__then__treat_it_as_a_business_rejection() {
        var f = new Fixture();
        f.providerFails(HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden", HttpHeaders.EMPTY,
                "{\"code\":\"REJECT_CARD_PAYMENT\",\"message\":\"synthetic\"}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        assertThat(f.service.confirmPayment(7L, f.request).getStatus()).isEqualTo("ABORTED");
        verify(f.store).failPayment("order-uuid"); verifyNoInteractions(f.compensations);
    }

    @Test void given__already_processed_and_a_recorded_payment__when__confirming__then__return_the_stored_done_from_a_short_read() {
        var f = new Fixture(); var existing = f.existing("synthetic-key");
        when(f.payments.findFirstByOrderIdAndStatusOrderByIdAsc("order-uuid", PaymentStatus.DONE))
                .thenReturn(Optional.empty()).thenReturn(Optional.of(existing));
        f.providerFails(HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request", HttpHeaders.EMPTY,
                "{\"code\":\"ALREADY_PROCESSED_PAYMENT\"}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        var result = f.service.confirmPayment(7L, f.request);
        assertThat(result.getStatus()).isEqualTo("DONE"); assertThat(result.getPaymentKey()).isEqualTo("synthetic-key");
        assertThat(f.events).containsExactly("order", "begin:ro", "commit", "provider", "begin:ro", "commit");
        verifyNoInteractions(f.store, f.compensations);
    }

    @Test void given__already_processed_without_a_recorded_payment__when__the_lookup_is_done__then__apply_it_in_the_writer() {
        var f = new Fixture(); var looked = f.done();
        f.providerFails(new IllegalStateException("S008 synthetic"));
        when(f.toss.getPayment(anyString(), eq("synthetic-key"))).thenAnswer(call -> { f.outside("lookup"); return looked; });
        assertThat(f.service.confirmPayment(7L, f.request)).isSameAs(looked);
        assertThat(f.events).containsExactly("order", "begin:ro", "commit", "provider", "begin:ro", "commit", "lookup",
                "begin:rw", "complete:200", "done-check", "payment", "commit", "rank");
        verifyNoInteractions(f.compensations);
    }

    @ParameterizedTest @ValueSource(strings = {"none", "ABORTED"})
    void given__already_processed_without_a_recorded_payment__when__the_lookup_is_not_done__then__record_and_never_cancel_the_order(String looked, CapturedOutput output) {
        var f = new Fixture();
        f.providerFails(HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request", HttpHeaders.EMPTY,
                "{\"code\":\"ALREADY_PROCESSED_PAYMENT\"}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        if (!looked.equals("none")) when(f.toss.getPayment(anyString(), anyString())).thenReturn(
                TossPaymentResponse.builder().paymentKey("synthetic-key").orderId("order-uuid").status(looked).build());
        assertThatThrownBy(() -> f.service.confirmPayment(7L, f.request)).hasMessage("Payment result is unknown. The order remains pending.");
        verifyNoInteractions(f.store); verify(f.toss, never()).cancelPayment(anyString(), anyString(), any());
        assertThat(f.savedRecord.getReason()).isEqualTo("ALREADY_PROCESSED_UNRECORDED");
        assertThat(output.getAll()).contains("PAYMENT_COMPENSATION_REQUIRED paymentKey=synthetic-key orderId=order-uuid amount=200 userId=7 stage=UNKNOWN_RESULT reason=ALREADY_PROCESSED_UNRECORDED recordId=41");
    }

    @ParameterizedTest @ValueSource(strings = {"400:ALREADY_PROCESSING_REQUEST", "400:", "408:", "400:PROVIDER_ERROR",
            "400:CARD_PROCESSING_ERROR", "400:SOME_FUTURE_CODE", "404:NOT_FOUND_PAYMENT"})
    void given__a_4xx_that_is_not_a_listed_rejection__when__confirming__then__resolve_by_lookup_and_keep_the_order_pending(String input) {
        var f = new Fixture(); var parts = input.split(":", -1);
        var body = parts[1].isEmpty() ? "<html>gateway</html>" : "{\"code\":\"" + parts[1] + "\",\"message\":\"synthetic\"}";
        f.providerFails(HttpClientErrorException.create(HttpStatus.valueOf(Integer.parseInt(parts[0])), "synthetic", HttpHeaders.EMPTY,
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        when(f.toss.getPayment(anyString(), eq("synthetic-key"))).thenReturn(
                TossPaymentResponse.builder().paymentKey("synthetic-key").orderId("order-uuid").status("IN_PROGRESS").build());
        assertThatThrownBy(() -> f.service.confirmPayment(7L, f.request)).hasMessage("Payment result is unknown. The order remains pending.");
        verify(f.toss).getPayment(anyString(), eq("synthetic-key")); verifyNoInteractions(f.store);
        assertThat(f.savedRecord.getStage()).isEqualTo(PaymentCompensationRecord.Stage.UNKNOWN_RESULT);
    }

    @ParameterizedTest @ValueSource(strings = {"REJECT_CARD_PAYMENT", "INVALID_CARD_NUMBER", "EXCEED_MAX_DAILY_PAYMENT_COUNT",
            "INVALID_STOPPED_CARD", "BELOW_MINIMUM_AMOUNT", "NOT_FOUND_PAYMENT_SESSION", "FDS_ERROR"})
    void given__a_listed_customer_rejection__when__confirming__then__cancel_the_order_without_lookup(String code) {
        var f = new Fixture();
        f.providerFails(HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request", HttpHeaders.EMPTY,
                ("{\"code\":\"" + code + "\"}").getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        assertThat(f.service.confirmPayment(7L, f.request).getStatus()).isEqualTo("ABORTED");
        verify(f.store).failPayment("order-uuid"); verify(f.toss, never()).getPayment(anyString(), anyString());
    }

    @Test void given__a_rejection_while_a_concurrent_request_already_paid__when__confirming__then__replay_the_recorded_done() {
        var f = new Fixture(); f.failCancels = false;
        when(f.payments.findFirstByOrderIdAndStatusOrderByIdAsc("order-uuid", PaymentStatus.DONE)).thenReturn(Optional.empty())
                .thenReturn(Optional.of(f.existing("concurrent-key")));
        f.providerFails(HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden", HttpHeaders.EMPTY,
                "{\"code\":\"REJECT_CARD_PAYMENT\"}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        var result = f.service.confirmPayment(7L, f.request);
        assertThat(result.getStatus()).isEqualTo("DONE"); assertThat(result.getPaymentKey()).isEqualTo("concurrent-key");
    }

    @Test void given__an_aborted_lookup_for_another_order__when__the_result_is_unknown__then__never_cancel_this_order() {
        var f = new Fixture();
        f.providerFails(new ResourceAccessException("synthetic"));
        when(f.toss.getPayment(anyString(), anyString())).thenReturn(TossPaymentResponse.builder().paymentKey("synthetic-key").orderId("other-order").status("ABORTED").build());
        assertThatThrownBy(() -> f.service.confirmPayment(7L, f.request)).hasMessage("Payment result is unknown. The order remains pending.");
        verifyNoInteractions(f.store);
    }

    @Test void given__ranking_fails_after_commit__when__the_payment_is_applied__then__still_return_the_approval() {
        var f = new Fixture(); var response = f.done(); f.provider(response); f.rankingFailure = new IllegalStateException("synthetic redis");
        assertThat(f.service.confirmPayment(7L, f.request)).isSameAs(response);
        verify(f.toss, never()).cancelPayment(anyString(), anyString(), any()); verifyNoInteractions(f.compensations);
    }

    @Test void given__a_timeout_and_a_done_lookup__when__confirming__then__apply_the_looked_up_payment() {
        var f = new Fixture(); var looked = f.done();
        f.providerFails(new ResourceAccessException("synthetic read timeout"));
        when(f.toss.getPayment(anyString(), eq("synthetic-key"))).thenAnswer(call -> { f.outside("lookup"); return looked; });
        var result = f.service.confirmPayment(7L, f.request);
        assertThat(result).isSameAs(looked);
        assertThat(f.events).containsExactly("order", "begin:ro", "commit", "provider", "lookup", "begin:rw", "complete:200", "done-check", "payment", "commit", "rank");
        verifyNoInteractions(f.compensations);
    }

    @Test void given__a_provider_5xx_and_an_aborted_lookup__when__confirming__then__treat_it_as_a_clear_failure() {
        var f = new Fixture();
        f.providerFails(HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "Bad Gateway", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8));
        when(f.toss.getPayment(anyString(), eq("synthetic-key"))).thenReturn(TossPaymentResponse.builder().paymentKey("synthetic-key").orderId("order-uuid").status("ABORTED").build());
        assertThat(f.service.confirmPayment(7L, f.request).getStatus()).isEqualTo("ABORTED");
        verify(f.store).failPayment("order-uuid"); verify(f.store, never()).completePayment(anyString(), anyLong());
    }

    @ParameterizedTest @ValueSource(strings = {"lookup-error", "lookup-in-progress", "lookup-other-order"})
    void given__an_unknown_provider_result__when__the_lookup_does_not_resolve_it__then__keep_the_order_pending_and_record_compensation(String kind, CapturedOutput output) {
        var f = new Fixture();
        f.providerFails(new ResourceAccessException("synthetic connect failure"));
        if (kind.equals("lookup-error")) when(f.toss.getPayment(anyString(), anyString())).thenThrow(new ResourceAccessException("synthetic"));
        else when(f.toss.getPayment(anyString(), anyString())).thenReturn(TossPaymentResponse.builder().paymentKey("synthetic-key")
                .orderId(kind.equals("lookup-other-order") ? "other-order" : "order-uuid").status(kind.equals("lookup-other-order") ? "DONE" : "IN_PROGRESS").build());
        assertThatThrownBy(() -> f.service.confirmPayment(7L, f.request)).isInstanceOf(RuntimeException.class)
                .hasMessage("Payment result is unknown. The order remains pending.");
        verifyNoInteractions(f.store); verify(f.toss, never()).cancelPayment(anyString(), anyString(), any());
        var record = f.savedRecord;
        assertThat(record.getStage()).isEqualTo(PaymentCompensationRecord.Stage.UNKNOWN_RESULT);
        assertThat(record.getReason()).isEqualTo("PROVIDER_RESULT_UNKNOWN");
        assertThat(record.getPaymentKey()).isEqualTo("synthetic-key"); assertThat(record.getOrderId()).isEqualTo("order-uuid");
        assertThat(record.getUserId()).isEqualTo(7L); assertThat(record.getAmount()).isEqualTo(200L);
        assertThat(record.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 10, 22, 10, 0)); assertThat(record.getResolvedAt()).isNull();
        assertThat(output.getAll()).contains("PAYMENT_COMPENSATION_REQUIRED paymentKey=synthetic-key orderId=order-uuid amount=200 userId=7 stage=UNKNOWN_RESULT reason=PROVIDER_RESULT_UNKNOWN recordId=41")
                .doesNotContain("synthetic-secret").doesNotContain("c3ludGhldGljLXNlY3JldDo=");
    }

    @Test void given__the_record_cannot_be_stored__when__compensation_is_required__then__still_log_the_fixed_line_with_no_record_id(CapturedOutput output) {
        var f = new Fixture();
        f.providerFails(new ResourceAccessException("synthetic"));
        when(f.toss.getPayment(anyString(), anyString())).thenThrow(new ResourceAccessException("synthetic"));
        doThrow(new DataIntegrityViolationException("synthetic")).when(f.compensations).saveAndFlush(any());
        assertThatThrownBy(() -> f.service.confirmPayment(7L, f.request)).hasMessage("Payment result is unknown. The order remains pending.");
        assertThat(output.getAll()).contains("PAYMENT_COMPENSATION_REQUIRED paymentKey=synthetic-key orderId=order-uuid amount=200 userId=7 stage=UNKNOWN_RESULT reason=PROVIDER_RESULT_UNKNOWN recordId=none");
    }

    @Test void given__the_locked_order_is_no_longer_payable__when__applying__then__roll_back_and_cancel_the_provider_payment() {
        var f = new Fixture(); f.provider(f.done()); f.completion = Completion.REJECTED;
        assertThatThrownBy(() -> f.service.confirmPayment(7L, f.request)).hasMessage("Payment processed but failed to save record. Payment Cancelled.");
        assertThat(f.events).containsExactly("order", "begin:ro", "commit", "provider", "begin:rw", "complete:200", "done-check", "rollback",
                "begin:ro", "key-check", "commit", "cancel");
        verify(f.payments, never()).saveAndFlush(any()); verifyNoInteractions(f.compensations);
    }

    @Test void given__the_same_key_is_already_recorded__when__applying_again__then__succeed_idempotently_without_cancel() {
        var f = new Fixture(); var response = f.done(); f.provider(response); f.completion = Completion.ALREADY_PAID;
        when(f.payments.findFirstByOrderIdAndStatusOrderByIdAsc("order-uuid", PaymentStatus.DONE)).thenReturn(Optional.empty())
                .thenAnswer(call -> { f.events.add("done-check"); return Optional.of(f.existing("synthetic-key")); });
        assertThat(f.service.confirmPayment(7L, f.request)).isSameAs(response);
        assertThat(f.events.getLast()).isEqualTo("commit");
        verify(f.payments, never()).saveAndFlush(any()); verify(f.toss, never()).cancelPayment(anyString(), anyString(), any());
    }

    @Test void given__another_key_already_paid_the_order__when__applying__then__roll_back_and_cancel_this_payment() {
        var f = new Fixture(); f.provider(f.done()); f.completion = Completion.ALREADY_PAID;
        when(f.payments.findFirstByOrderIdAndStatusOrderByIdAsc("order-uuid", PaymentStatus.DONE)).thenReturn(Optional.empty())
                .thenAnswer(call -> { f.events.add("done-check"); return Optional.of(f.existing("other-key")); });
        assertThatThrownBy(() -> f.service.confirmPayment(7L, f.request)).hasMessage("Payment processed but failed to save record. Payment Cancelled.");
        verify(f.toss).cancelPayment(anyString(), eq("synthetic-key"), any()); verify(f.payments, never()).saveAndFlush(any());
    }

    @Test void given__the_insert_fails_but_the_same_key_is_done__when__compensating__then__never_cancel_and_return_the_stored_payment() {
        var f = new Fixture(); f.provider(f.done());
        doThrow(new DataIntegrityViolationException("synthetic unique violation")).when(f.payments).saveAndFlush(any());
        when(f.payments.findByPaymentKey("synthetic-key")).thenReturn(Optional.of(f.existing("synthetic-key")));
        var result = f.service.confirmPayment(7L, f.request);
        assertThat(result.getStatus()).isEqualTo("DONE"); assertThat(result.getPaymentKey()).isEqualTo("synthetic-key");
        verify(f.toss, never()).cancelPayment(anyString(), anyString(), any()); verifyNoInteractions(f.compensations);
    }

    @Test void given__the_commit_fails_and_the_cancel_fails__when__compensating__then__record_cancel_failed(CapturedOutput output) {
        var f = new Fixture(); f.provider(f.done()); f.manager.commitFailures = 1; f.manager.commitFailureAfter = 1;
        doThrow(new ResourceAccessException("synthetic")).when(f.toss).cancelPayment(anyString(), anyString(), any());
        assertThatThrownBy(() -> f.service.confirmPayment(7L, f.request))
                .hasMessage("Payment processed but failed to save record. Payment cancellation failed.");
        assertThat(f.savedRecord.getStage()).isEqualTo(PaymentCompensationRecord.Stage.CANCEL_FAILED);
        assertThat(f.savedRecord.getReason()).isEqualTo("PERSIST_FAILED");
        assertThat(output.getAll()).contains("PAYMENT_COMPENSATION_REQUIRED paymentKey=synthetic-key orderId=order-uuid amount=200 userId=7 stage=CANCEL_FAILED reason=PERSIST_FAILED recordId=41");
    }

    @Test void given__writer_connections_fail_twice__when__applying__then__retry_with_short_pauses_and_succeed() {
        var f = new Fixture(); f.provider(f.done()); f.manager.writerBeginFailures = 2;
        assertThat(f.service.confirmPayment(7L, f.request).getStatus()).isEqualTo("DONE");
        assertThat(f.pauses).containsExactly(100L, 200L);
        verify(f.payments).saveAndFlush(any()); verify(f.toss, never()).cancelPayment(anyString(), anyString(), any());
    }

    @Test void given__no_connection_at_all_after_approval__when__applying__then__never_cancel_unverified_and_log_without_record(CapturedOutput output) {
        var f = new Fixture(); f.provider(f.done());
        doAnswer(call -> { f.manager.writerBeginFailures = 100; f.manager.readerBeginFailures = 100; f.outside("provider"); return f.done(); })
                .when(f.toss).confirmPayment(anyString(), any());
        assertThatThrownBy(() -> f.service.confirmPayment(7L, f.request)).hasMessage("Payment processed but its record could not be verified.");
        verify(f.toss, never()).cancelPayment(anyString(), anyString(), any()); verifyNoInteractions(f.store);
        assertThat(output.getAll()).contains("stage=UNKNOWN_RESULT reason=WRITER_UNAVAILABLE_UNVERIFIED recordId=none");
    }

    @Test void given__an_approval_for_another_order__when__confirming__then__never_touch_either_order_and_cancel_it() {
        var f = new Fixture(); var other = TossPaymentResponse.builder().paymentKey("synthetic-key").orderId("other-order").totalAmount(200L).status("DONE").build();
        f.provider(other);
        assertThatThrownBy(() -> f.service.confirmPayment(7L, f.request)).hasMessage("Payment processed but failed to save record. Payment Cancelled.");
        verifyNoInteractions(f.store); verify(f.toss).cancelPayment(anyString(), eq("synthetic-key"), any());
    }

    @Test void given__a_non_done_provider_status__when__confirming__then__return_it_unchanged_without_writes() {
        var f = new Fixture(); var waiting = TossPaymentResponse.builder().paymentKey("synthetic-key").orderId("order-uuid").status("WAITING_FOR_DEPOSIT").build();
        f.provider(waiting);
        assertThat(f.service.confirmPayment(7L, f.request)).isSameAs(waiting);
        verifyNoInteractions(f.store, f.compensations); verify(f.payments, never()).saveAndFlush(any());
    }

    @Test void given__client_supplied_identifiers__when__logging__then__reduce_them_to_one_bounded_token() {
        assertThat(JpaPayments.safe("bad\nkey=1 x")).isEqualTo("bad_key_1_x");
        assertThat(JpaPayments.safe("a".repeat(200))).hasSize(200);
        assertThat(JpaPayments.safe("a".repeat(250))).hasSize(200);
        assertThat(JpaPayments.safe(null)).isNull();
    }

    private static final class Fixture {
        final List<String> events = new ArrayList<>();
        final List<Long> pauses = new ArrayList<>();
        final PaymentRepository payments = mock(PaymentRepository.class);
        final PaymentCompensationRecordRepository compensations = mock(PaymentCompensationRecordRepository.class);
        final StorePaymentOrders orders = mock(StorePaymentOrders.class);
        final StorePaymentEvents store = mock(StorePaymentEvents.class);
        final TossPaymentsClient toss = mock(TossPaymentsClient.class);
        final RecordingManager manager = new RecordingManager(events);
        final TossPaymentConfirmRequest request = TossPaymentConfirmRequest.builder().paymentKey("synthetic-key").orderId("order-uuid").amount(200L).build();
        Completion completion = Completion.APPLIED;
        RuntimeException rankingFailure;
        boolean failCancels = true;
        Payment savedPayment;
        PaymentCompensationRecord savedRecord;
        final JpaPayments service;
        Fixture() {
            when(payments.findFirstByOrderIdAndStatusOrderByIdAsc(anyString(), any())).thenAnswer(call -> {
                if (manager.writing) events.add("done-check");
                return Optional.empty();
            });
            when(payments.findByPaymentKey(anyString())).thenAnswer(call -> { events.add("key-check"); return Optional.empty(); });
            order(new StorePaymentOrders.Order(8L, "order-uuid", 7L, 200L, "PENDING"));
            when(payments.saveAndFlush(any())).thenAnswer(call -> { events.add("payment"); savedPayment = call.getArgument(0); return savedPayment; });
            when(compensations.saveAndFlush(any())).thenAnswer(call -> {
                savedRecord = call.getArgument(0); ReflectionTestUtils.setField(savedRecord, "id", 41L); return savedRecord;
            });
            when(store.completePayment(anyString(), anyLong())).thenAnswer(call -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
                events.add("complete:" + call.getArgument(1));
                return new StorePaymentEvents.Completed(completion, () -> {
                    if (rankingFailure != null) throw rankingFailure;
                    outside("rank");
                });
            });
            when(store.failPayment(anyString())).thenAnswer(call -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                events.add("fail"); return failCancels;
            });
            when(toss.cancelPayment(anyString(), anyString(), any())).thenAnswer(call -> { outside("cancel"); return null; });
            var writes = new TransactionTemplate(manager);
            var reads = new TransactionTemplate(manager); reads.setReadOnly(true);
            service = new JpaPayments(toss, payments, compensations, orders, store, "synthetic-secret", writes, reads,
                    Clock.fixed(Instant.parse("2026-10-22T01:00:00Z"), ZoneId.of("Asia/Seoul")), pauses::add);
        }
        void order(StorePaymentOrders.Order value) {
            doAnswer(call -> { outside("order"); return value; }).when(orders).getOrder("order-uuid");
        }
        void provider(TossPaymentResponse response) {
            when(toss.confirmPayment(anyString(), any())).thenAnswer(call -> { outside("provider"); return response; });
        }
        void providerFails(RuntimeException failure) {
            when(toss.confirmPayment(anyString(), any())).thenAnswer(call -> { outside("provider"); throw failure; });
        }
        void outside(String event) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).as("no transaction around " + event).isFalse();
            events.add(event);
        }
        TossPaymentResponse done() {
            return TossPaymentResponse.builder().paymentKey("synthetic-key").orderId("order-uuid").mId("synthetic-merchant")
                    .currency("KRW").method("카드").totalAmount(200L).balanceAmount(200L).status("DONE")
                    .requestedAt(OffsetDateTime.parse("2026-10-21T01:00:00+09:00")).approvedAt(OffsetDateTime.parse("2026-10-21T01:00:01+09:00")).build();
        }
        Payment existing(String key) {
            var result = Payment.builder().paymentKey(key).orderId("order-uuid").userId(7L).amount(200L).method("카드")
                    .requestedAt(done().getRequestedAt().toLocalDateTime()).build();
            result.approve(done().getApprovedAt().toLocalDateTime()); return result;
        }
    }

    private static final class RecordingManager extends AbstractPlatformTransactionManager {
        final List<String> events;
        int writerBeginFailures, readerBeginFailures, commitFailures, commitFailureAfter, commits;
        boolean writing;
        RecordingManager(List<String> events) { this.events = events; }
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
            if (definition.isReadOnly() && readerBeginFailures > 0) { readerBeginFailures--; throw new CannotCreateTransactionException("synthetic pool timeout"); }
            if (!definition.isReadOnly() && writerBeginFailures > 0) { writerBeginFailures--; throw new CannotCreateTransactionException("synthetic pool timeout"); }
            writing = !definition.isReadOnly();
            events.add(definition.isReadOnly() ? "begin:ro" : "begin:rw");
        }
        @Override protected void doCommit(DefaultTransactionStatus status) {
            writing = false; events.add("commit"); commits++;
            if (commitFailures > 0 && commits > commitFailureAfter) { commitFailures--; throw new org.springframework.transaction.TransactionSystemException("synthetic commit failure"); }
        }
        @Override protected void doRollback(DefaultTransactionStatus status) { writing = false; events.add("rollback"); }
    }
}
