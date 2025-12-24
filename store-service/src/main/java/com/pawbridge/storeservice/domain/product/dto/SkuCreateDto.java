package com.pawbridge.storeservice.domain.product.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * SKU 생성 DTO (표준화 버전)
 * - 옵션 값을 ID로 지정
 */
@Getter
@NoArgsConstructor
public class SkuCreateDto {
    private String skuCode;
    private Long price;
    private Integer stockQuantity;
    
    // 옵션 값 ID 목록 (예: [1, 4] = Red + L)
    private List<Long> optionValueIds;
}
