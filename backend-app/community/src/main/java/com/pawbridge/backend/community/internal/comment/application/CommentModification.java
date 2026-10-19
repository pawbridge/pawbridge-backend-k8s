package com.pawbridge.backend.community.internal.comment.application;

public interface CommentModification {
    void update(long commentId, String content);
}
