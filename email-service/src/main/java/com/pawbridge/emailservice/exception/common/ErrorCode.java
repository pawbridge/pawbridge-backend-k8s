package com.pawbridge.emailservice.exception.common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {

    // EMAIL VERIFICATION
    EXPIRED_CODE(HttpStatus.BAD_REQUEST, "인증 코드가 만료되었습니다."),
    INVALID_CODE(HttpStatus.BAD_REQUEST, "인증 코드가 일치하지 않습니다."),
    TOO_MANY_SEND_ATTEMPTS(HttpStatus.TOO_MANY_REQUESTS, "인증 코드 발송 횟수를 초과했습니다. 5분 후 다시 시도해주세요."),
    TOO_MANY_VERIFY_ATTEMPTS(HttpStatus.TOO_MANY_REQUESTS, "인증 시도 횟수를 초과했습니다. 새로운 인증 코드를 요청해주세요."),

    // EMAIL SENDING
    EMAIL_SENDING_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "이메일 발송에 실패했습니다."),
    EMAIL_TEMPLATE_LOAD_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "이메일 템플릿 로드에 실패했습니다."),

    // VALIDATION
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다."),

    // 5xx
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 에러");

    private final HttpStatus httpStatus;
    private final String message;

    ErrorCode(HttpStatus httpStatus, String message) {
        this.httpStatus = httpStatus;
        this.message = message;
    }
}
