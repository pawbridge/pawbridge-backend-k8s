package com.pawbridge.backend.payment.internal.confirmation.infrastructure.jpa.repository

import com.pawbridge.backend.payment.internal.confirmation.infrastructure.jpa.model.Payment
import com.pawbridge.backend.payment.internal.confirmation.infrastructure.jpa.model.PaymentStatus
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

// Nullable parameters: Spring Data rejects null for non-null Kotlin parameters, which the Java interface accepted.
interface PaymentRepository : JpaRepository<Payment, Long> {
    fun findByPaymentKey(paymentKey: String?): Optional<Payment>

    fun findFirstByOrderIdAndStatusOrderByIdAsc(orderId: String?, status: PaymentStatus?): Optional<Payment>
}
