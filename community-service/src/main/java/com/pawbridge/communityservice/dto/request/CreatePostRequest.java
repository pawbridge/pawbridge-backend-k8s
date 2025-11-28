package com.pawbridge.communityservice.dto.request;

import com.pawbridge.communityservice.domain.entity.BoardType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 게시글 생성 요청 DTO
 * - 이미지는 MultipartFile[]로 별도로 받음
 */
public record CreatePostRequest(
        @NotBlank(message = "제목은 필수입니다")
        String title,

        @NotBlank(message = "내용은 필수입니다")
        String content,

        @NotNull(message = "게시판 타입은 필수입니다")
        BoardType boardType
) {
}
