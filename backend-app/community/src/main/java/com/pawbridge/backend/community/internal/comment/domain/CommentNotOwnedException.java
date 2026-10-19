package com.pawbridge.backend.community.internal.comment.domain;

public final class CommentNotOwnedException extends RuntimeException {
    public CommentNotOwnedException() {
        super("댓글에 접근할 권한이 없습니다.");
    }
}
