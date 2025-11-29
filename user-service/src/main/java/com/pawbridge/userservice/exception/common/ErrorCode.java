package com.pawbridge.userservice.exception.common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * @implNote 에러 메시지 코드들을 한번에 관리
 */

@Getter
public enum ErrorCode {

    // USER
    USER_NOT_FOUND(HttpStatus.BAD_REQUEST, "존재하지 않는 회원입니다."),
    USER_ALREADY_REGISTERED(HttpStatus.BAD_REQUEST, "이미 가입된 회원입니다."),
    USER_INVALID_ACCESS(HttpStatus.BAD_REQUEST, "잘못된 유저의 접근입니다."),

    // EMAIL
    EMAIL_NOT_VERIFIED(HttpStatus.BAD_REQUEST, "이메일 인증이 필요합니다."),

    // VALIDATION
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다."),

    // AUTH
    INCONSISTENT_PASSWORD(HttpStatus.BAD_REQUEST, "비밀번호가 일치하지 않습니다."),
    INVALID_PASSWORD(HttpStatus.BAD_REQUEST, "비밀번호가 틀렸습니다."),

    // JWT - Token
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "토큰이 만료되었습니다."),
    TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰입니다."),
    REFRESH_TOKEN_NOT_FOUND(HttpStatus.NOT_FOUND, "리프레시 토큰을 찾을 수 없습니다."),
    REFRESH_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "리프레시 토큰이 만료되었습니다."),

    // myPage
    CURRENT_PASSWORD_NOT_MATCH(HttpStatus.BAD_REQUEST, "현재 비밀번호가 일치하지 않습니다."),
    NEW_PASSWORD_SAME_AS_OLD(HttpStatus.BAD_REQUEST, "새 비밀번호가 기존 비밀번호와 동일합니다."),
    NEW_PASSWORD_NOT_MATCH(HttpStatus.BAD_REQUEST, "변경을 위해 입력하신 비밀번호와 다릅니다."),
    NICKNAME_ALREADY_EXISTS(HttpStatus.BAD_REQUEST, "중복된 닉네임입니다."),
    NICKNAME_CHANGE_NOT_AVAILABLE(HttpStatus.BAD_REQUEST, "닉네임 변경 불가기간(6개월)이 지나지 않았습니다."),

    // 신규 추가 - 비밀번호 찾기/수정
    EMAIL_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "이메일 서비스를 일시적으로 사용할 수 없습니다."),
    OAUTH_USER_CANNOT_CHANGE_PASSWORD(HttpStatus.BAD_REQUEST, "소셜 로그인 사용자는 비밀번호를 변경할 수 없습니다."),
    SAME_PASSWORD_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "현재 비밀번호와 동일한 비밀번호로 변경할 수 없습니다."),
    INVALID_CURRENT_PASSWORD(HttpStatus.BAD_REQUEST, "현재 비밀번호가 일치하지 않습니다."),
    PASSWORD_RESET_CODE_INVALID(HttpStatus.BAD_REQUEST, "비밀번호 재설정 인증코드가 유효하지 않습니다."),
    NICKNAME_DUPLICATE(HttpStatus.CONFLICT, "이미 사용 중인 닉네임입니다."),
    EMAIL_DUPLICATE(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다."),

    // EMAIL VERIFICATION (email-service 통합)
    EXPIRED_CODE(HttpStatus.BAD_REQUEST, "인증 코드가 만료되었습니다."),
    INVALID_CODE(HttpStatus.BAD_REQUEST, "인증 코드가 일치하지 않습니다."),
    TOO_MANY_SEND_ATTEMPTS(HttpStatus.TOO_MANY_REQUESTS, "인증 코드 발송 횟수를 초과했습니다. 5분 후 다시 시도해주세요."),
    TOO_MANY_VERIFY_ATTEMPTS(HttpStatus.TOO_MANY_REQUESTS, "인증 시도 횟수를 초과했습니다. 새로운 인증 코드를 요청해주세요."),

    // EMAIL SENDING (email-service 통합)
    EMAIL_SENDING_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "이메일 발송에 실패했습니다."),
    EMAIL_TEMPLATE_LOAD_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "이메일 템플릿 로드에 실패했습니다."),

    // 5xx
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 에러");

    private final HttpStatus httpStatus;
    private final String message;

    ErrorCode(HttpStatus httpStatus, String message) {
        this.httpStatus = httpStatus;
        this.message = message;
    }
}
