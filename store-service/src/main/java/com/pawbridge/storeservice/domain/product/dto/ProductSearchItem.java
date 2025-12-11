package com.pawbridge.storeservice.domain.product.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductSearchItem {
    private Long id;            // 상품 ID (grouping key)
    private Long skuId;         // SKU ID (unique key)
    private String name;        // 상품명
    private String optionName;  // 옵션명 (예: "Red, L")
    private String description; // 설명
    private String imageUrl;    // 이미지 URL
    private Long price;         // 판매가
    private Integer totalStock; // 재고 수량
    private String status;      // 판매 상태
    
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;
}
