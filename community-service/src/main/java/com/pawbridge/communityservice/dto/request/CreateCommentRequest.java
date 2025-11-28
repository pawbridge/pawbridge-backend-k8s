package com.pawbridge.communityservice.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 댓글 생성 요청 DTO
 */
public record CreateCommentRequest(
        @NotBlank(message = "내용은 필수입니다")
        String content
) {
}
