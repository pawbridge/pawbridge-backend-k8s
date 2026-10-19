package com.pawbridge.backend.user.internal.nickname.application;

public final class NicknameDuplicateException extends RuntimeException {
    public NicknameDuplicateException() {
        super("이미 사용 중인 닉네임입니다.");
    }
}
