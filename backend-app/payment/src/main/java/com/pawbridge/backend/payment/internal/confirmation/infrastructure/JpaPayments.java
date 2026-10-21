package com.pawbridge.backend.payment.internal.confirmation.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.store.api.StorePaymentOrders;
import com.pawbridge.backend.payment.internal.confirmation.application.Payments;
import org.springframework.transaction.support.TransactionTemplate;
import com.pawbridge.backend.payment.internal.confirmation.infrastructure.toss.TossPaymentsClient;
import com.pawbridge.backend.payment.internal.confirmation.infrastructure.jpa.model.Outbox;
import com.pawbridge.backend.payment.internal.confirmation.infrastructure.jpa.repository.OutboxRepository;
import com.pawbridge.backend.payment.internal.confirmation.application.dto.TossPaymentCancelRequest;
import com.pawbridge.backend.payment.internal.confirmation.application.dto.TossPaymentConfirmRequest;
import com.pawbridge.backend.payment.internal.confirmation.application.dto.TossPaymentResponse;
import com.pawbridge.backend.payment.internal.confirmation.infrastructure.jpa.model.Payment;
import com.pawbridge.backend.payment.internal.confirmation.infrastructure.jpa.model.PaymentStatus;
import com.pawbridge.backend.payment.internal.confirmation.infrastructure.jpa.repository.PaymentRepository;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

/** Relocated original workflow. Known payment safety limitations remain; activation is a separate gate. */
public final class JpaPayments implements Payments {

    private final TossPaymentsClient tossPaymentsClient;
    private final PaymentRepository paymentRepository;
    private final OutboxRepository outboxRepository;
    private final StorePaymentOrders storeServiceClient;
    private final ObjectMapper objectMapper;

    private final String tossSecretKey;
    private final TransactionTemplate writes;

    public JpaPayments(TossPaymentsClient tossPaymentsClient, PaymentRepository paymentRepository,
            OutboxRepository outboxRepository, StorePaymentOrders storeServiceClient,
            ObjectMapper objectMapper, String tossSecretKey, TransactionTemplate writes) {
        this.tossPaymentsClient = tossPaymentsClient;
        this.paymentRepository = paymentRepository;
        this.outboxRepository = outboxRepository;
        this.storeServiceClient = storeServiceClient;
        this.objectMapper = objectMapper;
        this.tossSecretKey = tossSecretKey;
        this.writes = writes;
    }

    @Override public TossPaymentResponse confirmPayment(Long userId, TossPaymentConfirmRequest request) {
        return writes.execute(status -> confirmWithinTransaction(userId, request));
    }

    private TossPaymentResponse confirmWithinTransaction(Long userId, TossPaymentConfirmRequest request) {
        String orderId = request.getOrderId();
        Long amount = request.getAmount();
        Optional<Payment> existingPayment = paymentRepository.findByOrderId(orderId);
        if (existingPayment.isPresent()) {
            Payment payment = existingPayment.get();
            if (payment.getStatus() == PaymentStatus.DONE) {
                if (!payment.getAmount().equals(amount)) {
                    throw new IllegalStateException("Payment exists but amount mismatch");
                }
                return TossPaymentResponse.builder()
                        .paymentKey(payment.getPaymentKey())
                        .orderId(payment.getOrderId())
                        .totalAmount(payment.getAmount())
                        .status("DONE")
                        .requestedAt(payment.getRequestedAt().atOffset(java.time.ZoneOffset.of("+09:00")))
                        .approvedAt(payment.getApprovedAt().atOffset(java.time.ZoneOffset.of("+09:00")))
                        .build();
            }
        }
        StorePaymentOrders.Order orderInfo = storeServiceClient.getOrder(orderId);
        if (!orderInfo.totalAmount().equals(amount)) {

            throw new IllegalStateException("Payment Amount Mismatch");
        }
        String encodedKey = Base64.getEncoder().encodeToString((tossSecretKey + ":").getBytes(StandardCharsets.UTF_8));
        String authorization = "Basic " + encodedKey;
        TossPaymentResponse response = null;
        try {
            response = tossPaymentsClient.confirmPayment(authorization, request);
        } catch (Exception e) {
            String errorMsg = e.getMessage();
            if (errorMsg != null && (errorMsg.contains("S008") || errorMsg.contains("ALREADY_PROCESSED_PAYMENT"))) {
                Optional<Payment> foundPayment = paymentRepository.findByOrderId(request.getOrderId());
                if (foundPayment.isEmpty()) {
                     if (e instanceof RuntimeException) {
                         throw (RuntimeException) e;
                     } else {
                         throw new RuntimeException("Payment failed and not found in DB", e);
                     }
                }
                Payment previousPayment = foundPayment.get();

                return TossPaymentResponse.builder()
                        .paymentKey(previousPayment.getPaymentKey())
                        .orderId(previousPayment.getOrderId())
                        .totalAmount(previousPayment.getAmount())
                        .status("DONE") // 이미 완료된 상태
                        .requestedAt(previousPayment.getRequestedAt().atOffset(java.time.ZoneOffset.of("+09:00")))
                        .approvedAt(previousPayment.getApprovedAt().atOffset(java.time.ZoneOffset.of("+09:00")))
                        .build();
            }
            try {
                savePaymentFailureAndOutbox(request.getPaymentKey(), request.getOrderId(), "PAYMENT_FAILED");
            } catch (JsonProcessingException ex) {
                // Preserve the original swallowed serialization failure; no durable recovery is added.
            }
            return TossPaymentResponse.builder()
                    .status("ABORTED")
                    .orderId(request.getOrderId())
                    .paymentKey(request.getPaymentKey())
                    .build(); 
        }

        if (!response.getStatus().equals("DONE")) {

             return response;
        }
        try {
            savePaymentAndOutbox(userId, response);
        } catch (Exception e) {
            cancelPayment(response.getPaymentKey(), "System Error during saving payment record");
            try {
                savePaymentFailureAndOutbox(response.getPaymentKey(), response.getOrderId(), "PAYMENT_FAILED");
            } catch (JsonProcessingException ex) {
                // Preserve the original swallowed serialization failure; no durable recovery is added.
            }
            
            throw new RuntimeException("Payment processed but failed to save record. Payment Cancelled.", e);
        }

        return response;
    }

    private void savePaymentAndOutbox(Long userId, TossPaymentResponse response) throws JsonProcessingException {
        Payment payment = Payment.builder()
                .paymentKey(response.getPaymentKey())
                .orderId(response.getOrderId())
                .userId(userId)
                .amount(response.getTotalAmount())
                .method(response.getMethod())
                .requestedAt(response.getRequestedAt().toLocalDateTime())
                .build();
        
        payment.approve(response.getApprovedAt().toLocalDateTime());
        paymentRepository.save(payment);
        String payload = objectMapper.writeValueAsString(response);
        Outbox outbox = Outbox.builder()
                .aggregateType("PAYMENT")
                .aggregateId(response.getPaymentKey())
                .eventType("PAYMENT_COMPLETED")
                .payload(payload)
                .build();
        outboxRepository.save(outbox);
    }

    private void savePaymentFailureAndOutbox(String paymentKey, String orderId, String eventType) throws JsonProcessingException {
        TossPaymentResponse failureResponse = TossPaymentResponse.builder()
                .paymentKey(paymentKey)
                .orderId(orderId)
                .status("ABORTED") // 실패 상태로 변경
                .build();

        String payload = objectMapper.writeValueAsString(failureResponse);
        Outbox outbox = Outbox.builder()
                .aggregateType("PAYMENT") // AggregateType
                .aggregateId(paymentKey != null ? paymentKey : "UNKNOWN_" + orderId) // ID
                .eventType(eventType)
                .payload(payload)
                .build();
        outboxRepository.save(outbox);
    }
    private void cancelPayment(String paymentKey, String reason) {

        try {
            String encodedKey = Base64.getEncoder().encodeToString((tossSecretKey + ":").getBytes(StandardCharsets.UTF_8));
            String authorization = "Basic " + encodedKey;
            TossPaymentCancelRequest request = TossPaymentCancelRequest.builder()
                    .cancelReason(reason)
                    .build();
            
            tossPaymentsClient.cancelPayment(authorization, paymentKey, request);

        } catch (Exception e) {
            // Original best-effort compensation: failure is not resolved or recorded durably here.
        }
    }
}
