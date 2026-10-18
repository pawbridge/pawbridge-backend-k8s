package com.pawbridge.animalservice.dto.response;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Page 직렬화 오류 방지용 커스텀 페이지 응답 DTO
 * - Spring Boot 3.x에서 PageImpl 내부의 Sort 직렬화 오류 회피
 * - 프론트엔드가 사용하는 필드 구조를 그대로 유지
 */
public record PageResponse<T>(
        List<T> content,
        int number,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {
    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast()
        );
    }
}
