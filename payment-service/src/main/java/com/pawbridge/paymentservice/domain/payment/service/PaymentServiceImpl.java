package com.pawbridge.paymentservice.domain.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.paymentservice.client.TossPaymentsClient;
import com.pawbridge.paymentservice.common.entity.Outbox;
import com.pawbridge.paymentservice.common.repository.OutboxRepository;
import com.pawbridge.paymentservice.domain.payment.dto.TossPaymentConfirmRequest;
import com.pawbridge.paymentservice.domain.payment.dto.TossPaymentResponse;
import com.pawbridge.paymentservice.domain.payment.entity.Payment;
import com.pawbridge.paymentservice.domain.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private final TossPaymentsClient tossPaymentsClient;
    private final PaymentRepository paymentRepository;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @Value("${toss.secret-key}")
    private String tossSecretKey;

    @Transactional
    public TossPaymentResponse confirmPayment(Long userId, TossPaymentConfirmRequest request) {
        // 1. Encode Secret Key (Basic Auth)
        String encodedKey = Base64.getEncoder().encodeToString((tossSecretKey + ":").getBytes(StandardCharsets.UTF_8));
        String authorization = "Basic " + encodedKey;

        // 2. Call Toss API
        TossPaymentResponse response = tossPaymentsClient.confirmPayment(authorization, request);

        if (response.getStatus().equals("DONE")) {
            // 3. Save Payment Entity
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

            // 4. Save Outbox Event (PAYMENT_COMPLETED)
            try {
                String payload = objectMapper.writeValueAsString(response);
                Outbox outbox = Outbox.builder()
                        .aggregateType("PAYMENT")
                        .aggregateId(response.getPaymentKey())
                        .eventType("PAYMENT_COMPLETED")
                        .payload(payload)
                        .build();
                outboxRepository.save(outbox);
            } catch (JsonProcessingException e) {
                log.error("Failed to create outbox event for payment", e);
                throw new RuntimeException("Payment confirmed but failed to save event", e);
            }
        } else {
            // Handle failure or other statuses if needed
            log.warn("Payment not DONE: status={}", response.getStatus());
        }

        return response;
    }
}
