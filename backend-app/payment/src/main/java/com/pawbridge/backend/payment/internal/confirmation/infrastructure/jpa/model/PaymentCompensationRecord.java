package com.pawbridge.backend.payment.internal.confirmation.infrastructure.jpa.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** A provider payment that may need manual cancellation or reconciliation. Never stores provider text, card data or secrets. */
@Entity
@Getter
@Table(name = "payment_compensation_records", schema = "pawbridge_payment")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentCompensationRecord {
    public enum Stage { CANCEL_FAILED, UNKNOWN_RESULT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 200)
    private String paymentKey;

    @Column(length = 200)
    private String orderId;

    private Long userId;

    private Long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Stage stage;

    @Column(nullable = false, length = 50)
    private String reason;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime resolvedAt;

    public PaymentCompensationRecord(String paymentKey, String orderId, Long userId, Long amount, Stage stage,
            String reason, LocalDateTime createdAt) {
        this.paymentKey = paymentKey;
        this.orderId = orderId;
        this.userId = userId;
        this.amount = amount;
        this.stage = stage;
        this.reason = reason;
        this.createdAt = createdAt;
    }
}
