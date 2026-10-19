package com.pawbridge.backend.community.internal.post.application;

public final class InvalidPostSearchInputException extends RuntimeException {
    public InvalidPostSearchInputException() {
        super("검색 입력을 확인해 주세요.");
    }

    public InvalidPostSearchInputException(Throwable cause) {
        super("검색 입력을 확인해 주세요.", cause);
    }
}
