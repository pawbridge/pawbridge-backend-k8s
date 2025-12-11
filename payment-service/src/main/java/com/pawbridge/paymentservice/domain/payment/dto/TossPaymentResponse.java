package com.pawbridge.paymentservice.domain.payment.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.AllArgsConstructor;
import lombok.Builder;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.OffsetDateTime;

@Getter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TossPaymentResponse {
    private String paymentKey;
    private String orderId;
    private String mId;
    private String currency;
    private String method; // 카드, 계좌이체 등
    private Long totalAmount;
    private Long balanceAmount;
    private String status; // READY, DONE, CANCELED, ABORTED
    private OffsetDateTime requestedAt;
    private OffsetDateTime approvedAt;
    private TossFailure failure; // 결제 실패 시 정보
}
