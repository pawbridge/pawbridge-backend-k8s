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

/** A provider payment that may need manual cancellation or reconciliation. Never stores provider text, card data or secrets. */
@Entity
@Table(name = "payment_compensation_records", schema = "pawbridge_payment")
class PaymentCompensationRecord(
    paymentKey: String?,
    orderId: String?,
    userId: Long?,
    amount: Long?,
    stage: Stage?,
    reason: String?,
    createdAt: LocalDateTime?,
) {
    enum class Stage { CANCEL_FAILED, UNKNOWN_RESULT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @Column(length = 200)
    var paymentKey: String? = paymentKey
        protected set

    @Column(length = 200)
    var orderId: String? = orderId
        protected set

    var userId: Long? = userId
        protected set

    var amount: Long? = amount
        protected set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    var stage: Stage? = stage
        protected set

    @Column(nullable = false, length = 50)
    var reason: String? = reason
        protected set

    @Column(nullable = false)
    var createdAt: LocalDateTime? = createdAt
        protected set

    var resolvedAt: LocalDateTime? = null
        protected set
}
