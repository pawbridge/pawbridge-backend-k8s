package com.pawbridge.communityservice.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 게시글 수정 요청 DTO
 * - 이미지는 MultipartFile[]로 별도로 받음
 */
public record UpdatePostRequest(
        @NotBlank(message = "제목은 필수입니다")
        String title,

        @NotBlank(message = "내용은 필수입니다")
        String content
) {
}
