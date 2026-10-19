package com.pawbridge.backend.community.internal.post.application;

public final class PostSearchUnavailableException extends RuntimeException {
    public PostSearchUnavailableException() {
        super("검색 서비스를 사용할 수 없습니다.");
    }

    public PostSearchUnavailableException(Throwable cause) {
        super("검색 서비스를 사용할 수 없습니다.", cause);
    }
}
