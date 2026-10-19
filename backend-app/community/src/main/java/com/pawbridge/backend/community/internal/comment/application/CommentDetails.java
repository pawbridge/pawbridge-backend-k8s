package com.pawbridge.backend.community.internal.comment.application;

import java.time.LocalDateTime;

public record CommentDetails(
        long commentId,
        long postId,
        long authorId,
        String authorNickname,
        String content,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
