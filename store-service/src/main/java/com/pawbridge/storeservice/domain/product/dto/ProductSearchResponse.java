package com.pawbridge.storeservice.domain.product.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;
import java.time.LocalDateTime;

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
}
