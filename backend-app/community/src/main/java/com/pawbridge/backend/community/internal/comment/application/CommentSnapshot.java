package com.pawbridge.backend.community.internal.comment.application;

import java.time.LocalDateTime;

public record CommentSnapshot(
        long commentId,
        long postId,
        long authorId,
        String content,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
