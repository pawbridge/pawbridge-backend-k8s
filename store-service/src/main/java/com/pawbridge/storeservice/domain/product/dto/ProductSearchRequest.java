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
public class ProductSearchRequest {

    private String keyword;              // 검색어 (상품명, 설명)
    private Long minPrice;               // 최소 가격
    private Long maxPrice;               // 최대 가격
    private List<String> categories;     // 카테고리 필터 (향후 확장용)
    private Boolean inStockOnly;         // 재고 있는 상품만 (기본값: true)
    private String sortBy;               // 정렬 기준 (price, createdAt)
    private String sortOrder;            // 정렬 순서 (asc, desc)

    @Builder.Default
    private Integer page = 0;            // 페이지 번호 (0부터 시작)

    @Builder.Default
    private Integer size = 20;           // 페이지 크기
}
