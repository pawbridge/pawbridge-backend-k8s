package com.pawbridge.apigateway.util;

import lombok.Builder;
import lombok.Getter;

/**
 * API Gateway 에러 응답 DTO
 * - 다른 서비스의 ResponseDTO와 동일한 구조
 */
@Getter
@Builder
public class ErrorResponse {
    private final int code;
    private final String message;
    private final Object data;

    public static ErrorResponse of(int code, String message) {
        return ErrorResponse.builder()
                .code(code)
                .message(message)
                .data(null)
                .build();
    }
}
