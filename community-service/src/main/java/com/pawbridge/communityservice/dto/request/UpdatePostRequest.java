package com.pawbridge.communityservice.dto.request;

import jakarta.validation.constraints.Size;

/**
 * 게시글 수정 요청 DTO
 * - 모든 필드가 Optional (부분 수정 가능)
 * - 이미지는 MultipartFile[]로 별도로 받음
 * - null이 아닌 필드만 업데이트됨
 */
public record UpdatePostRequest(
        @Size(min = 1, max = 200, message = "제목은 1자 이상 200자 이하여야 합니다")
        String title,

        @Size(min = 1, message = "내용은 1자 이상이어야 합니다")
        String content
) {
}
