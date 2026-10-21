package com.pawbridge.backend.payment.internal.confirmation.application.dto;

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
