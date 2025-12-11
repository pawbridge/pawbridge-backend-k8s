package com.pawbridge.paymentservice.domain.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.paymentservice.client.StoreServiceClient;
import com.pawbridge.paymentservice.client.TossPaymentsClient;
import com.pawbridge.paymentservice.common.entity.Outbox;
import com.pawbridge.paymentservice.common.repository.OutboxRepository;
import com.pawbridge.paymentservice.domain.payment.dto.StoreOrderResponse;
import com.pawbridge.paymentservice.domain.payment.dto.TossPaymentCancelRequest;
import com.pawbridge.paymentservice.domain.payment.dto.TossPaymentConfirmRequest;
import com.pawbridge.paymentservice.domain.payment.dto.TossPaymentResponse;
import com.pawbridge.paymentservice.domain.payment.entity.Payment;
import com.pawbridge.paymentservice.domain.payment.entity.PaymentStatus;
import com.pawbridge.paymentservice.domain.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private final TossPaymentsClient tossPaymentsClient;
    private final PaymentRepository paymentRepository;
    private final OutboxRepository outboxRepository;
    private final StoreServiceClient storeServiceClient; // 이중 확인용 (Double Check)
    private final ObjectMapper objectMapper;

    @Value("${toss.secret-key}")
    private String tossSecretKey;

    @Transactional
    public TossPaymentResponse confirmPayment(Long userId, TossPaymentConfirmRequest request) {
        String orderId = request.getOrderId();
        Long amount = request.getAmount();

        // 🛡️ 안전장치 1: 멱등성 (Idempotency)
        // 이미 결제가 존재하는지 확인
        Optional<Payment> existingPayment = paymentRepository.findByOrderId(orderId);
        if (existingPayment.isPresent()) {
            Payment payment = existingPayment.get();
            if (payment.getStatus() == PaymentStatus.DONE) {
                log.info("Payment already processed for orderId: {}", orderId);
                // 정책에 따라 더미 응답 반환 또는 예외 발생
                // 여기서는 금액이 일치하는지 확인 후 성공 응답 반환
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

        // 🛡️ 안전장치 2: 이중 검증 (스토어 서비스와 교차 검증)
        StoreOrderResponse orderInfo = storeServiceClient.getOrder(orderId); // 스토어 서비스의 orderUuid (String)
        if (!orderInfo.getTotalAmount().equals(amount)) {
            log.error("Payment Verification Failed! Request: {}, Real: {}", amount, orderInfo.getTotalAmount());
            throw new IllegalStateException("Payment Amount Mismatch");
        }

        // 1. 시크릿 키 인코딩 (Basic Auth)
        String encodedKey = Base64.getEncoder().encodeToString((tossSecretKey + ":").getBytes(StandardCharsets.UTF_8));
        String authorization = "Basic " + encodedKey;

        // 2. 토스 API 호출 (실제 결제 승인)
        TossPaymentResponse response = null;
        try {
            response = tossPaymentsClient.confirmPayment(authorization, request);
        } catch (Exception e) {
            log.warn("Toss Payment Failed: {}", e.getMessage());

            // [S008] 중복 요청 처리 (이미 처리 중이거나 완료된 건)
            // 에러를 던지면 프론트엔드가 혼란스러워 하므로, 이미 저장된 성공 정보를 조회해서 "성공(DONE)"으로 응답함 (멱등성 보장)
            String errorMsg = e.getMessage();
            if (errorMsg != null && (errorMsg.contains("S008") || errorMsg.contains("ALREADY_PROCESSED_PAYMENT"))) {
                log.info("Duplicate Request detected ([S008]). Retrieving existing payment for idempotency.");
                
                // Payment existingPayment = paymentRepository.findByOrderId(request.getOrderId())
                //         .orElseThrow(() -> new RuntimeException("Payment not found", e)); 
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

            // [진짜 실패] 잔액 부족, 네트워크 오류 등 -> 재고 복구 필요
            // 트랜잭션을 커밋시키기 위해 예외를 던지지 않고 'ABORTED' 응답을 반환함.
            try {
                savePaymentFailureAndOutbox(request.getPaymentKey(), request.getOrderId(), "PAYMENT_FAILED");
            } catch (JsonProcessingException ex) {
                log.error("Failed to save failure outbox during API Error", ex);
            }
            
            // Controller가 200 OK와 함께 ABORTED 상태를 반환하게 함 (프론트에서 처리 필요)
            return TossPaymentResponse.builder()
                    .status("ABORTED")
                    .orderId(request.getOrderId())
                    .paymentKey(request.getPaymentKey())
                    .build(); 
        }

        if (!response.getStatus().equals("DONE")) {
             log.warn("Payment status is not DONE: {}", response.getStatus());
             return response;
        }

        // 3. DB 저장 (메인 DB + Outbox) 및 보상 트랜잭션
        try {
            savePaymentAndOutbox(userId, response);
        } catch (Exception e) {
            log.error("DB Save Failed after Toss Payment! Triggering Compensation...", e);
            
            // 1. 보상 트랜잭션 (결제 취소)
            cancelPayment(response.getPaymentKey(), "System Error during saving payment record");

            // 2. 실패 이벤트 발행 (재고 복구용)
            try {
                savePaymentFailureAndOutbox(response.getPaymentKey(), response.getOrderId(), "PAYMENT_FAILED");
            } catch (JsonProcessingException ex) {
                log.error("Failed to save failure outbox", ex);
            }
            
            throw new RuntimeException("Payment processed but failed to save record. Payment Cancelled.", e);
        }

        return response;
    }

    private void savePaymentAndOutbox(Long userId, TossPaymentResponse response) throws JsonProcessingException {
        // A. 결제 엔티티 저장
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

        // B. Outbox 이벤트 저장 (PAYMENT_COMPLETED)
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
        // Outbox 이벤트 저장 (PAYMENT_FAILED)
        // 실패 시에는 상태를 ABORTED 등으로 변경해서 보낼 수도 있음
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
    
    // 보상 트랜잭션 메서드 (Fallback 취소)
    private void cancelPayment(String paymentKey, String reason) {
        log.warn(">>> TRIGGERING PAYMENT CANCELLATION for key: {}, reason: {}", paymentKey, reason);
        
        try {
            // 1. 시크릿 키 인코딩
            String encodedKey = Base64.getEncoder().encodeToString((tossSecretKey + ":").getBytes(StandardCharsets.UTF_8));
            String authorization = "Basic " + encodedKey;

            // 2. 토스 취소 API 호출
            TossPaymentCancelRequest request = TossPaymentCancelRequest.builder()
                    .cancelReason(reason)
                    .build();
            
            tossPaymentsClient.cancelPayment(authorization, paymentKey, request);
            
            log.info(">>> PAYMENT CANCELLED SUCCESSFULLY for key: {}", paymentKey);
        } catch (Exception e) {
            log.error(">>> CRITICAL: FAILED TO CANCEL PAYMENT during compensation! Manual intervention required. Key: {}", paymentKey, e);
            // In a real system, we might save this to a "Dead Letter Queue" or "Failed Operations Table" for manual ops.
        }
    }
}
