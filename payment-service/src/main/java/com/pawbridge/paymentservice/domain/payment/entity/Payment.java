package com.pawbridge.paymentservice.domain.payment.entity;

import com.pawbridge.paymentservice.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@Table(name = "payments")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String paymentKey; // Toss Payments Key

    @Column(nullable = false, length = 36)
    private String orderId; // Store Service Order UUID

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(length = 20)
    private String method; // CARD, VIRTUAL_ACCOUNT etc.

    private LocalDateTime requestedAt;
    private LocalDateTime approvedAt;

    @Builder
    public Payment(String paymentKey, String orderId, Long userId, Long amount, String method, LocalDateTime requestedAt) {
        this.paymentKey = paymentKey;
        this.orderId = orderId;
        this.userId = userId;
        this.amount = amount;
        this.method = method;
        this.requestedAt = requestedAt;
        this.status = PaymentStatus.READY;
    }

    public void approve(LocalDateTime approvedAt) {
        this.status = PaymentStatus.DONE;
        this.approvedAt = approvedAt;
    }

    public void cancel() {
        this.status = PaymentStatus.CANCELED;
    }
}
