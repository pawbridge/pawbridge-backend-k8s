package com.pawbridge.backend.community.internal.comment.application;

public interface CommentWriter {
    CommentSnapshot create(long postId, long authorId, String content);
}
