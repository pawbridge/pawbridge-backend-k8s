package com.pawbridge.storeservice.domain.product.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 상품 생성 요청 DTO (표준화 버전)
 */
@Getter
@NoArgsConstructor
public class ProductCreateRequest {
    private String name;
    private String description;
    private String imageUrl;
    private Long categoryId;
    
    // SKU 목록 (옵션 값 ID로 조합)
    private List<SkuCreateDto> skus;
}
