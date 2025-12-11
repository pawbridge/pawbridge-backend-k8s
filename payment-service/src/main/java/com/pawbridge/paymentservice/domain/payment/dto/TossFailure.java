package com.pawbridge.paymentservice.domain.payment.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Getter
@NoArgsConstructor
@ToString
public class TossFailure {
    private String code;
    private String message;
}
