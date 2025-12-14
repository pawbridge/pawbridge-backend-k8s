package com.pawbridge.communityservice.dto.response;

import com.pawbridge.communityservice.domain.entity.Comment;

import java.time.LocalDateTime;

/**
 * 댓글 응답 DTO
 */
public record CommentResponse(
        Long commentId,
        Long postId,
        Long authorId,
        String authorNickname,
        String content,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static CommentResponse fromEntity(Comment comment, String authorNickname) {
        return new CommentResponse(
                comment.getCommentId(),
                comment.getPostId(),
                comment.getAuthorId(),
                authorNickname,
                comment.getContent(),
                comment.getCreatedAt(),
                comment.getUpdatedAt()
        );
    }
}
