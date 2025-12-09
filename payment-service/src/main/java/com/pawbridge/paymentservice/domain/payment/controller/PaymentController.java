package com.pawbridge.paymentservice.domain.payment.controller;

import com.pawbridge.paymentservice.domain.payment.dto.TossPaymentConfirmRequest;
import com.pawbridge.paymentservice.domain.payment.dto.TossPaymentResponse;
import com.pawbridge.paymentservice.domain.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping("/confirm")
    public ResponseEntity<TossPaymentResponse> confirmPayment(
            @RequestHeader("X-User-Id") Long userId,
            @RequestBody TossPaymentConfirmRequest request) {
        TossPaymentResponse response = paymentService.confirmPayment(userId, request);
        return ResponseEntity.ok(response);
    }
}
