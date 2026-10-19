package com.pawbridge.backend.community.internal.comment.application;

public final class UnsupportedCommentCharactersException extends RuntimeException {
    public UnsupportedCommentCharactersException(Throwable cause) {
        super("일부 이모지 등 지원하지 않는 문자를 제외해 주세요.", cause);
    }
}
