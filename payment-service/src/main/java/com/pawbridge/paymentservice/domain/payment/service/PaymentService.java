package com.pawbridge.paymentservice.domain.payment.service;

import com.pawbridge.paymentservice.domain.payment.dto.TossPaymentConfirmRequest;
import com.pawbridge.paymentservice.domain.payment.dto.TossPaymentResponse;

public interface PaymentService {
    TossPaymentResponse confirmPayment(Long userId, TossPaymentConfirmRequest request);
}
