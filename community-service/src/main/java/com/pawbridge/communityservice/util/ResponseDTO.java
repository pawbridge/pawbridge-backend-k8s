package com.pawbridge.communityservice.util;

import com.pawbridge.communityservice.exception.common.ErrorCode;
import lombok.Builder;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class ResponseDTO<T> {

    private final int code;
    private final String message;
    private final T data;

    @Builder
    private ResponseDTO(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    // 성공 응답 (메시지만 반환)
    public static ResponseDTO<Void> ok() {
        return ResponseDTO.<Void>builder()
                .code(HttpStatus.OK.value())
                .message(null)
                .build();
    }

    // 성공 응답 (메시지만 반환)
    public static ResponseDTO<Void> okWithMessage(String message) {
        return ResponseDTO.<Void>builder()
                .code(HttpStatus.OK.value())
                .message(message)
                .build();
    }

    // 1. 메시지 없이 호출하는 경우
    public static <T> ResponseDTO<T> okWithData(T data) {
        return okWithData(data, "요청이 성공적으로 처리되었습니다.");
    }

    // 2. 메시지와 함께 호출하는 경우 (실제 로직)
    public static <T> ResponseDTO<T> okWithData(T data, String message) {
        return ResponseDTO.<T>builder()
                .code(HttpStatus.OK.value())
                .message(message)
                .data(data)
                .build();
    }

    // 에러 응답
    public static ResponseDTO<Void> error(ErrorCode errorCode) {
        return ResponseDTO.<Void>builder()
                .code(errorCode.getHttpStatus().value())
                .message(errorCode.getMessage())
                .build();
    }

    public static ResponseDTO<Void> errorWithMessage(HttpStatus httpStatus, String errorMessage) {
        return ResponseDTO.<Void>builder()
                .code(httpStatus.value())
                .message(errorMessage)
                .data(null)
                .build();
    }
}
