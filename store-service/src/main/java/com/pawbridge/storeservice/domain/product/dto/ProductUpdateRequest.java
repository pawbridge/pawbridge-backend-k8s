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
    
    // Proper specific SKU update list
    private java.util.List<SkuUpdateDto> skus;

    @Getter
    @NoArgsConstructor
    public static class SkuUpdateDto {
        private Long id; // SKU ID (Required to identify which SKU to update)
        private Long price;
        private Integer stockQuantity;
        private String skuCode; // Optional update
    }
}
