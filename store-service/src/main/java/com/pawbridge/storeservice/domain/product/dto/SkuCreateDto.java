package com.pawbridge.storeservice.domain.product.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import java.util.Map;

@Getter
@NoArgsConstructor
public class SkuCreateDto {
    private String skuCode;
    private Long price;
    private Integer stockQuantity;
    // Key: 옵션 그룹명 (Color), Value: 옵션 값 (Red)
    private Map<String, String> options;
}
