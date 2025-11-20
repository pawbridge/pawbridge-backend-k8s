package com.pawbridge.emailservice.util;

import com.pawbridge.emailservice.exception.common.ErrorCode;
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

    public static ResponseDTO<Void> ok() {
        return ResponseDTO.<Void>builder()
                .code(HttpStatus.OK.value())
                .message(null)
                .build();
    }

    public static ResponseDTO<Void> okWithMessage(String message) {
        return ResponseDTO.<Void>builder()
                .code(HttpStatus.OK.value())
                .message(message)
                .build();
    }

    public static <T> ResponseDTO<T> okWithData(T data) {
        return okWithData(data, "요청이 성공적으로 처리되었습니다.");
    }

    public static <T> ResponseDTO<T> okWithData(T data, String message) {
        return ResponseDTO.<T>builder()
                .code(HttpStatus.OK.value())
                .message(message)
                .data(data)
                .build();
    }

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
