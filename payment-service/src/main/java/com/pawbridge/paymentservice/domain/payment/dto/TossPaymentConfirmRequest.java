package com.pawbridge.paymentservice.domain.payment.dto;

import lombok.AllArgsConstructor;
import jakarta.validation.constraints.Pattern;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TossPaymentConfirmRequest {
    @Pattern(regexp = "[\\u0000-\\uFFFF]*", message = "일부 이모지 등 지원하지 않는 문자를 제외해 주세요.")
    private String paymentKey;
    @Pattern(regexp = "[\\u0000-\\uFFFF]*", message = "일부 이모지 등 지원하지 않는 문자를 제외해 주세요.")
    private String orderId;
    private Long amount;
}
