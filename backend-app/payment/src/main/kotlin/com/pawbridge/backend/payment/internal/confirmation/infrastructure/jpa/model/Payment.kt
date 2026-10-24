package com.pawbridge.backend.payment.internal.confirmation.infrastructure.jpa.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

// kotlin-jpa adds the no-arg constructor Hibernate uses; it leaves every field null, like the
// former protected Lombok constructor.
@Entity
@Table(name = "payments", schema = "pawbridge_payment")
class Payment(
    paymentKey: String?,
    orderId: String?,
    userId: Long?,
    amount: Long?,
    method: String?,
    requestedAt: LocalDateTime?,
) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @Column(nullable = false, unique = true, length = 100)
    var paymentKey: String? = paymentKey // 토스 페이먼트 키
        protected set

    @Column(nullable = false, length = 36)
    var orderId: String? = orderId // 스토어 서비스 주문 UUID
        protected set

    @Column(nullable = false)
    var userId: Long? = userId
        protected set

    @Column(nullable = false)
    var amount: Long? = amount
        protected set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: PaymentStatus? = PaymentStatus.READY
        protected set

    @Column(length = 20)
    var method: String? = method // 카드, 가상계좌 등
        protected set

    var requestedAt: LocalDateTime? = requestedAt
        protected set

    var approvedAt: LocalDateTime? = null
        protected set

    fun approve(approvedAt: LocalDateTime?) {
        this.status = PaymentStatus.DONE
        this.approvedAt = approvedAt
    }

    fun cancel() {
        this.status = PaymentStatus.CANCELED
    }

    class PaymentBuilder internal constructor() {
        private var paymentKey: String? = null
        private var orderId: String? = null
        private var userId: Long? = null
        private var amount: Long? = null
        private var method: String? = null
        private var requestedAt: LocalDateTime? = null

        fun paymentKey(paymentKey: String?): PaymentBuilder = apply {
            this.paymentKey = paymentKey
        }

        fun orderId(orderId: String?): PaymentBuilder = apply {
            this.orderId = orderId
        }

        fun userId(userId: Long?): PaymentBuilder = apply {
            this.userId = userId
        }

        fun amount(amount: Long?): PaymentBuilder = apply {
            this.amount = amount
        }

        fun method(method: String?): PaymentBuilder = apply {
            this.method = method
        }

        fun requestedAt(requestedAt: LocalDateTime?): PaymentBuilder = apply {
            this.requestedAt = requestedAt
        }

        fun build(): Payment = Payment(paymentKey, orderId, userId, amount, method, requestedAt)
    }

    companion object {
        @JvmStatic
        fun builder(): PaymentBuilder = PaymentBuilder()
    }
}
