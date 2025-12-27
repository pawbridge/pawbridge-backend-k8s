package com.pawbridge.storeservice.domain.product.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 카테고리 수정 요청 DTO
 */
@Getter
@NoArgsConstructor
public class CategoryUpdateRequest {
    private String name;
    private String description;
    private Long parentId; // null이면 루트 카테고리로 변경
}
