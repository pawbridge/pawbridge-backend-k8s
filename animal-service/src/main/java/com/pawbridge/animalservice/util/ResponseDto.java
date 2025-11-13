package com.pawbridge.animalservice.util;

import com.pawbridge.animalservice.exception.ErrorCode;
import lombok.Builder;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class ResponseDto<T> {
    private final int code;
    private final String message;
    private final T data;

    @Builder
    private ResponseDto(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    // 성공 응답 (메시지만 반환)
    public static ResponseDto<Void> ok() {
        return ResponseDto.<Void>builder()
                .code(HttpStatus.OK.value())
                .message(null)
                .build();
    }

    // 성공 응답 (메시지만 반환)
    public static ResponseDto<Void> okWithMessage(String message) {
        return ResponseDto.<Void>builder()
                .code(HttpStatus.OK.value())
                .message(message)
                .build();
    }

    // 1. 메시지 없이 호출하는 경우
    public static <T> ResponseDto<T> okWithData(T data) {
        return okWithData(data, "요청이 성공적으로 처리되었습니다.");
    }

    // 2. 메시지와 함께 호출하는 경우 (실제 로직)
    public static <T> ResponseDto<T> okWithData(T data, String message) {
        return ResponseDto.<T>builder()
                .code(HttpStatus.OK.value())
                .message(message)
                .data(data)
                .build();
    }

    // 에러 응답
    public static ResponseDto<Void> error(ErrorCode errorCode) {
        return ResponseDto.<Void>builder()
                .code(errorCode.getHttpStatus().value())
                .message(errorCode.getMessage())
                .build();
    }

    public static ResponseDto<Void> errorWithMessage(HttpStatus httpStatus, String errorMessage) {
        return ResponseDto.<Void>builder()
                .code(httpStatus.value())
                .message(errorMessage)
                .data(null)
                .build();
    }
}
