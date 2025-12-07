package com.pawbridge.storeservice.domain.product.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductSearchResponse {

    private List<ProductSearchItem> items;  // 검색 결과 목록
    private Long totalCount;                // 전체 결과 수
    private Integer currentPage;            // 현재 페이지
    private Integer totalPages;             // 전체 페이지 수
    private Boolean hasNext;                // 다음 페이지 여부

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProductSearchItem {
        private Long id;            // Product ID (grouping key)
        private Long skuId;         // SKU ID (unique key)
        private String name;        // Product Name
        private String optionName;  // Option Name (e.g., "Red, L")
        private String description;
        private String imageUrl;
        private Long price;         // SKU Price
        private Integer totalStock; // SKU Stock
        private String status;
    }
}
