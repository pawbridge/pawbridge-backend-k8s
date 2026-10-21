package com.pawbridge.backend.payment.internal.confirmation.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.payment.internal.confirmation.application.dto.*;
import com.pawbridge.backend.payment.internal.confirmation.infrastructure.jpa.model.*;
import com.pawbridge.backend.payment.internal.confirmation.infrastructure.jpa.repository.*;
import com.pawbridge.backend.payment.internal.confirmation.infrastructure.toss.TossPaymentsClient;
import com.pawbridge.backend.store.api.StorePaymentOrders;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/** Synthetic original-branch protection only; no database, provider, Kafka or safety remediation. */
class JpaPaymentsTest {
    @Test void given__a_matching_store_order__when__provider_returns_done__then__save_payment_and_original_outbox_before_local_commit() throws Exception {
        var f = new Fixture();
        var response = f.done();
        when(f.toss.confirmPayment(anyString(), same(f.request))).thenAnswer(call -> { f.events.add("provider"); return response; });
        var result = f.service.confirmPayment(7L, f.request);
        assertThat(result).isSameAs(response);
        assertThat(f.events).containsExactly("begin", "order", "provider", "payment", "outbox", "commit");
        var payment = f.savedPayment;
        assertThat(payment.getUserId()).isEqualTo(7L);
        assertThat(payment.getOrderId()).isEqualTo("order-uuid");
        assertThat(payment.getPaymentKey()).isEqualTo("synthetic-key");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.DONE);
        assertThat(payment.getAmount()).isEqualTo(200L);
        assertThat(payment.getRequestedAt()).isEqualTo(response.getRequestedAt().toLocalDateTime());
        assertThat(payment.getApprovedAt()).isEqualTo(response.getApprovedAt().toLocalDateTime());
        assertThat(f.rows).hasSize(1);
        var row = f.rows.getFirst();
        assertThat(row.getAggregateType()).isEqualTo("PAYMENT");
        assertThat(row.getAggregateId()).isEqualTo("synthetic-key");
        assertThat(row.getEventType()).isEqualTo("PAYMENT_COMPLETED");
        assertThat(f.mapper.readTree(row.getPayload())).isEqualTo(f.mapper.readTree(f.mapper.writeValueAsString(response)));
        verify(f.toss).confirmPayment(eq("Basic c3ludGhldGljLXNlY3JldDo="), same(f.request));
        verifyNoMoreInteractions(f.toss);
    }

    @Test void given__an_existing_done_payment__when__repeating_the_original_order_and_amount__then__return_stored_subset_without_store_or_provider() {
        var f = new Fixture(); var existing = f.existing();
        when(f.payments.findByOrderId("order-uuid")).thenReturn(Optional.of(existing));
        var result = f.service.confirmPayment(7L, f.request);
        assertThat(result.getStatus()).isEqualTo("DONE");
        assertThat(result.getPaymentKey()).isEqualTo(existing.getPaymentKey());
        assertThat(result.getTotalAmount()).isEqualTo(200L);
        assertThat(result.getRequestedAt()).isEqualTo(existing.getRequestedAt().atOffset(java.time.ZoneOffset.ofHours(9)));
        assertThat(result.getApprovedAt()).isEqualTo(existing.getApprovedAt().atOffset(java.time.ZoneOffset.ofHours(9)));
        assertThat(result.getMethod()).isNull();
        verifyNoInteractions(f.orders, f.toss, f.outbox);
        assertThat(f.events).containsExactly("begin", "commit");
    }

    @Test void given__an_existing_done_payment_with_different_amount__when__confirming__then__reject_before_store_or_provider() {
        var f = new Fixture(); when(f.payments.findByOrderId("order-uuid")).thenReturn(Optional.of(f.existing()));
        var different = TossPaymentConfirmRequest.builder().paymentKey("synthetic-key").orderId("order-uuid").amount(201L).build();
        assertThatThrownBy(() -> f.service.confirmPayment(7L, different)).isInstanceOf(IllegalStateException.class).hasMessage("Payment exists but amount mismatch");
        verifyNoInteractions(f.orders, f.toss, f.outbox);
        assertThat(f.events).containsExactly("begin", "rollback");
    }

    @Test void given__a_store_amount_mismatch__when__confirming__then__reject_without_provider_or_outbox() {
        var f = new Fixture(); when(f.orders.getOrder("order-uuid")).thenReturn(new StorePaymentOrders.Order(8L,"order-uuid",300L,"PENDING"));
        assertThatThrownBy(() -> f.service.confirmPayment(7L, f.request)).isInstanceOf(IllegalStateException.class).hasMessage("Payment Amount Mismatch");
        verifyNoInteractions(f.toss, f.outbox);
        verify(f.payments, never()).save(any());
        assertThat(f.events.getLast()).isEqualTo("rollback");
    }

    @Test void given__a_store_lookup_failure__when__confirming__then__propagate_before_provider_or_failure_event() {
        var f = new Fixture(); var failure = new IllegalStateException("synthetic store failure");
        when(f.orders.getOrder("order-uuid")).thenThrow(failure);
        assertThatThrownBy(() -> f.service.confirmPayment(7L, f.request)).isSameAs(failure);
        verifyNoInteractions(f.toss, f.outbox);
        assertThat(f.events.getLast()).isEqualTo("rollback");
    }

    @Test void given__a_non_done_provider_response__when__confirming__then__preserve_original_passthrough_without_payment_or_event() {
        var f = new Fixture(); var response = TossPaymentResponse.builder().status("READY").build();
        when(f.toss.confirmPayment(anyString(), any())).thenReturn(response);
        assertThat(f.service.confirmPayment(7L, f.request)).isSameAs(response);
        verify(f.payments, never()).save(any()); verifyNoInteractions(f.outbox);
        assertThat(f.events.getLast()).isEqualTo("commit");
    }

    @Test void given__an_ordinary_provider_exception__when__confirming__then__preserve_legacy_aborted_and_failed_outbox_without_claiming_outcome_is_known() throws Exception {
        var f = new Fixture(); when(f.toss.confirmPayment(anyString(), any())).thenThrow(new IllegalStateException("synthetic transport failure"));
        var result = f.service.confirmPayment(7L, f.request);
        assertThat(result.getStatus()).isEqualTo("ABORTED");
        assertThat(result.getOrderId()).isEqualTo("order-uuid");
        verify(f.payments, never()).save(any());
        assertThat(f.rows).hasSize(1);
        assertThat(f.rows.getFirst().getEventType()).isEqualTo("PAYMENT_FAILED");
        assertThat(f.mapper.readTree(f.rows.getFirst().getPayload()).get("status").asText()).isEqualTo("ABORTED");
        assertThat(f.events.getLast()).isEqualTo("commit");
    }

    @ParameterizedTest @ValueSource(strings = {"S008", "ALREADY_PROCESSED_PAYMENT"})
    void given__an_original_duplicate_error_with_a_stored_row__when__confirming__then__return_stored_done_subset_without_new_writes(String code) {
        var f = new Fixture();
        when(f.payments.findByOrderId("order-uuid")).thenReturn(Optional.empty(), Optional.of(f.existing()));
        when(f.toss.confirmPayment(anyString(), any())).thenThrow(new IllegalStateException(code));
        assertThat(f.service.confirmPayment(7L, f.request).getStatus()).isEqualTo("DONE");
        verify(f.payments, never()).save(any()); verifyNoInteractions(f.outbox);
    }

    @ParameterizedTest @ValueSource(strings = {"S008", "ALREADY_PROCESSED_PAYMENT"})
    void given__an_original_duplicate_error_without_a_stored_row__when__confirming__then__rethrow_without_failure_event(String code) {
        var f = new Fixture(); var failure = new IllegalStateException(code);
        when(f.toss.confirmPayment(anyString(), any())).thenThrow(failure);
        assertThatThrownBy(() -> f.service.confirmPayment(7L, f.request)).isSameAs(failure);
        verifyNoInteractions(f.outbox);
        assertThat(f.events.getLast()).isEqualTo("rollback");
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void given__done_then_save_failure__when__compensating__then__attempt_original_cancel_and_failed_outbox_before_rollback_even_if_cancel_fails(boolean cancelFails) {
        var f = new Fixture(); when(f.toss.confirmPayment(anyString(), any())).thenReturn(f.done());
        when(f.payments.save(any())).thenThrow(new IllegalStateException("synthetic save failure"));
        if (cancelFails) when(f.toss.cancelPayment(anyString(), anyString(), any())).thenThrow(new IllegalStateException("synthetic cancel failure"));
        assertThatThrownBy(() -> f.service.confirmPayment(7L, f.request)).isInstanceOf(RuntimeException.class)
                .hasMessage("Payment processed but failed to save record. Payment Cancelled.");
        verify(f.toss).cancelPayment(eq("Basic c3ludGhldGljLXNlY3JldDo="),eq("synthetic-key"),argThat(value -> value.getCancelReason().equals("System Error during saving payment record")));
        assertThat(f.rows).hasSize(1); assertThat(f.rows.getFirst().getEventType()).isEqualTo("PAYMENT_FAILED");
        assertThat(f.events.getLast()).isEqualTo("rollback");
        // Captured save invocation is not a durable failure record; the local transaction rolls back.
    }

    @Test void given__a_provider_failure_and_failure_payload_serialization_error__when__confirming__then__preserve_original_aborted_without_outbox() throws Exception {
        var f = new Fixture(); var mapper = mock(ObjectMapper.class);
        when(mapper.writeValueAsString(any())).thenThrow(new JsonProcessingException("synthetic") {});
        var service = f.service(mapper);
        when(f.toss.confirmPayment(anyString(), any())).thenThrow(new IllegalStateException("synthetic transport failure"));
        assertThat(service.confirmPayment(7L,f.request).getStatus()).isEqualTo("ABORTED");
        verifyNoInteractions(f.outbox); assertThat(f.events.getLast()).isEqualTo("commit");
    }

    @Test void given__provider_done_and_successful_save_calls__when__the_local_commit_fails__then__preserve_original_gap_without_running_inner_cancel_catch() {
        var f = new Fixture(); when(f.toss.confirmPayment(anyString(), any())).thenReturn(f.done());
        f.manager.commitFailure = new TransactionSystemException("synthetic commit failure");
        assertThatThrownBy(() -> f.service.confirmPayment(7L,f.request)).isSameAs(f.manager.commitFailure);
        verify(f.toss, never()).cancelPayment(anyString(), anyString(), any());
        assertThat(f.rows).hasSize(1); assertThat(f.rows.getFirst().getEventType()).isEqualTo("PAYMENT_COMPLETED");
        // This records the inherited boundary, not successful persistence or external cancellation.
    }

    @ParameterizedTest @ValueSource(strings = {"S008", "ALREADY_PROCESSED_PAYMENT"})
    void given__an_actual_provider_400_duplicate_body__when__the_http_adapter_reaches_confirmation__then__preserve_original_stored_done_branch(String providerCode) {
        var f = new Fixture();
        when(f.payments.findByOrderId("order-uuid")).thenReturn(Optional.empty(), Optional.of(f.existing()));
        var builder = org.springframework.web.client.RestClient.builder().baseUrl("https://provider.invalid/v1");
        var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("https://provider.invalid/v1/payments/confirm"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.method(org.springframework.http.HttpMethod.POST))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"" + providerCode + "\",\"message\":\"synthetic duplicate request\"}"));
        var client = new com.pawbridge.backend.payment.internal.confirmation.infrastructure.toss.HttpTossPaymentsClient(builder.build());
        var service = new JpaPayments(client, f.payments, f.outbox, f.orders, f.mapper, "synthetic-secret", new TransactionTemplate(f.manager));
        var result = service.confirmPayment(7L, f.request);
        assertThat(result.getStatus()).isEqualTo("DONE");
        assertThat(result.getPaymentKey()).isEqualTo("stored-synthetic-key");
        assertThat(result.getTotalAmount()).isEqualTo(200L);
        verify(f.payments, times(2)).findByOrderId("order-uuid");
        verify(f.payments, never()).save(any());
        verifyNoInteractions(f.outbox, f.toss);
        assertThat(f.events).containsExactly("begin", "order", "commit");
        server.verify();
    }

    private static final class Fixture {
        final List<String> events = new ArrayList<>();
        final PaymentRepository payments = mock(PaymentRepository.class);
        final OutboxRepository outbox = mock(OutboxRepository.class);
        final StorePaymentOrders orders = mock(StorePaymentOrders.class);
        final TossPaymentsClient toss = mock(TossPaymentsClient.class);
        final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        final RecordingManager manager = new RecordingManager(events);
        final List<Outbox> rows = new ArrayList<>();
        final TossPaymentConfirmRequest request = TossPaymentConfirmRequest.builder().paymentKey("synthetic-key").orderId("order-uuid").amount(200L).build();
        Payment savedPayment;
        final JpaPayments service;
        Fixture() {
            when(payments.findByOrderId("order-uuid")).thenReturn(Optional.empty());
            when(orders.getOrder("order-uuid")).thenAnswer(call -> { events.add("order");return new StorePaymentOrders.Order(8L,"order-uuid",200L,"PENDING"); });
            when(payments.save(any())).thenAnswer(call -> { events.add("payment");savedPayment=call.getArgument(0);return savedPayment; });
            when(outbox.save(any())).thenAnswer(call -> { events.add("outbox");Outbox row=call.getArgument(0);rows.add(row);return row; });
            service=service(mapper);
        }
        JpaPayments service(ObjectMapper selected) { return new JpaPayments(toss,payments,outbox,orders,selected,"synthetic-secret",new TransactionTemplate(manager)); }
        TossPaymentResponse done() {
            return TossPaymentResponse.builder().paymentKey("synthetic-key").orderId("order-uuid").mId("synthetic-merchant")
                    .currency("KRW").method("카드").totalAmount(200L).balanceAmount(200L).status("DONE")
                    .requestedAt(OffsetDateTime.parse("2026-10-21T01:00:00+09:00")).approvedAt(OffsetDateTime.parse("2026-10-21T01:00:01+09:00")).build();
        }
        Payment existing() {
            var result=Payment.builder().paymentKey("stored-synthetic-key").orderId("order-uuid").userId(7L).amount(200L).method("카드")
                    .requestedAt(done().getRequestedAt().toLocalDateTime()).build();
            result.approve(done().getApprovedAt().toLocalDateTime());return result;
        }
    }
    private static final class RecordingManager extends AbstractPlatformTransactionManager {
        final List<String> events; RuntimeException commitFailure;
        RecordingManager(List<String> events) { this.events=events; }
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { events.add("begin"); }
        @Override protected void doCommit(DefaultTransactionStatus status) { events.add("commit");if(commitFailure!=null)throw commitFailure; }
        @Override protected void doRollback(DefaultTransactionStatus status) { events.add("rollback"); }
    }
}
