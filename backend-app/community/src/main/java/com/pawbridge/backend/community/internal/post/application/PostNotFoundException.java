package com.pawbridge.backend.community.internal.post.application;

public final class PostNotFoundException extends RuntimeException {
    public PostNotFoundException() {
        super("게시글을 찾을 수 없습니다.");
    }
}
