package com.pawbridge.userservice.exception;

/**
 * 권한 없음 예외
 * - 특정 권한이 필요한 작업에 접근할 수 없을 때 발생
 */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException() {
        super("권한이 없습니다.");
    }

    public UnauthorizedException(String message) {
        super(message);
    }

    public UnauthorizedException(String message, Throwable cause) {
        super(message, cause);
    }
}
