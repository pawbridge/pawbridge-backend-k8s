package com.pawbridge.backend.user.internal.password.application;

public final class InvalidCurrentPasswordException extends RuntimeException {
    public InvalidCurrentPasswordException() {
        super("현재 비밀번호가 일치하지 않습니다.");
    }
}
