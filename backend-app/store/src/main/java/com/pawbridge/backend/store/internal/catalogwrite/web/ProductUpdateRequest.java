package com.pawbridge.backend.store.internal.catalogwrite.web;

import com.pawbridge.backend.store.internal.memberread.infrastructure.jpa.model.ProductStatus;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class ProductUpdateRequest {
    private String name;
    private String description;
    private String imageUrl;
    private ProductStatus status;
    private Long categoryId;
    
    // 개별 SKU 업데이트 목록
    private java.util.List<SkuUpdateDto> skus;
}
