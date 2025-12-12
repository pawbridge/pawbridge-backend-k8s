package com.pawbridge.communityservice.dto.response;

import com.pawbridge.communityservice.domain.entity.BoardType;
import com.pawbridge.communityservice.domain.entity.Post;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 게시글 응답 DTO
 */
public record PostResponse(
        Long postId,
        Long authorId,
        String authorNickname,
        String title,
        String content,
        BoardType boardType,
        List<String> imageUrls,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static PostResponse fromEntity(Post post, String authorNickname) {
        return new PostResponse(
                post.getPostId(),
                post.getAuthorId(),
                authorNickname,
                post.getTitle(),
                post.getContent(),
                post.getBoardType(),
                post.getImageUrls(),
                post.getCreatedAt(),
                post.getUpdatedAt()
        );
    }
}
