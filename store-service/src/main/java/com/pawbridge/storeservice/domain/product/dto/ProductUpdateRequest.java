package com.pawbridge.storeservice.domain.product.dto;

import com.pawbridge.storeservice.domain.product.entity.ProductStatus;
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
