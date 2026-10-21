package com.pawbridge.backend.store.internal.catalogwrite.web;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class CategoryCreateRequest {
    private String name;
    private String description;
    private Long parentId; // 선택 사항 (없으면 루트)
}
