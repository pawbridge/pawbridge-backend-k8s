package com.pawbridge.storeservice.domain.product.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class SkuUpdateDto {
    private Long id; // SKU ID (수정할 SKU 식별을 위해 필수)
    private Long price;
    private Integer stockQuantity;
    private String skuCode; // 선택적 수정
}
