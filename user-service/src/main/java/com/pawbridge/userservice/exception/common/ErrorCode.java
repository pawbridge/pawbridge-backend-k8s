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

    // 5xx
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 에러");

    private final HttpStatus httpStatus;
    private final String message;

    ErrorCode(HttpStatus httpStatus, String message) {
        this.httpStatus = httpStatus;
        this.message = message;
    }
}
