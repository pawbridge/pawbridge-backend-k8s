package com.pawbridge.backend.payment.internal.confirmation.infrastructure.jpa.repository;

import com.pawbridge.backend.payment.internal.confirmation.infrastructure.jpa.model.Payment;
import com.pawbridge.backend.payment.internal.confirmation.infrastructure.jpa.model.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByPaymentKey(String paymentKey);
    Optional<Payment> findFirstByOrderIdAndStatusOrderByIdAsc(String orderId, PaymentStatus status);
}
