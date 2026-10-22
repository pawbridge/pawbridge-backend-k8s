package com.pawbridge.backend.user.internal.password.application;

public final class OAuthUserCannotChangePasswordException extends RuntimeException {
    public OAuthUserCannotChangePasswordException() {
        super("소셜 로그인 사용자는 비밀번호를 변경할 수 없습니다.");
    }
}
