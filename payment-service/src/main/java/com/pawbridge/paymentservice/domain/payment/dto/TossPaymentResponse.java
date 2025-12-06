package com.pawbridge.paymentservice.domain.payment.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.OffsetDateTime;

@Getter
@ToString
@NoArgsConstructor
public class TossPaymentResponse {
    private String paymentKey;
    private String orderId;
    private String mId;
    private String currency;
    private String method; // 카드, 간편결제 등
    private Long totalAmount;
    private Long balanceAmount;
    private String status; // READY, DONE, CANCELED, ABORTED
    private OffsetDateTime requestedAt;
    private OffsetDateTime approvedAt;
    private TossFailure failure; // 결제 실패 시 정보

    @Getter
    @NoArgsConstructor
    @ToString
    public static class TossFailure {
        private String code;
        private String message;
    }
}
