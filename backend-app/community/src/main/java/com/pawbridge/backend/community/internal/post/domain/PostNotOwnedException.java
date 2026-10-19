package com.pawbridge.backend.community.internal.post.domain;

public final class PostNotOwnedException extends RuntimeException {
    public PostNotOwnedException() {
        super("게시글에 접근할 권한이 없습니다.");
    }
}
