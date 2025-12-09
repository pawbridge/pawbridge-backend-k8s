package com.pawbridge.storeservice.domain.product.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductEventPayload {
    private Long skuId;
    private Long productId;
    private String productName;
    private String skuCode;
    private String optionName; // e.g., "Color:Red, Size:L"
    private Long price;
    private Integer stockQuantity;
    private Boolean isPrimarySku; // 대표 SKU 여부
    private String status;
    private String imageUrl;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}