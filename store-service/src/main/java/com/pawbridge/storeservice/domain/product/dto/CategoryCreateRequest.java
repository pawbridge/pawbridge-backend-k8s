package com.pawbridge.storeservice.domain.product.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class CategoryCreateRequest {
    private String name;
    private Long parentId; // 선택 사항 (없으면 루트)
}
