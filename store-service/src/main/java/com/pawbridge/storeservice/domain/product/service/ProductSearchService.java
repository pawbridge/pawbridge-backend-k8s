package com.pawbridge.storeservice.domain.product.service;

import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.json.JsonData;
import com.pawbridge.storeservice.domain.product.dto.ProductSearchRequest;
import com.pawbridge.storeservice.domain.product.dto.ProductSearchResponse;
import com.pawbridge.storeservice.domain.product.dto.ProductSearchItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.client.elc.NativeQueryBuilder;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductSearchService {

    private final ElasticsearchOperations elasticsearchOperations;

    public ProductSearchResponse searchProducts(ProductSearchRequest request) {
        log.info(">>> [Service] Building search query for: {}", request);

        // 1. Elasticsearch 쿼리 생성
        NativeQuery searchQuery = buildSearchQuery(request);

        // [DEBUG] 생성된 쿼리 DSL 로그
        log.info(">>> [Service] Generated Query DSL: {}", searchQuery.getQuery().toString());

        // 2. 검색 실행
        log.info(">>> [Service] Executing Elasticsearch query...");
        SearchHits<Map> searchHits = elasticsearchOperations.search(
            searchQuery,
            Map.class,
            org.springframework.data.elasticsearch.core.mapping.IndexCoordinates.of("store.outbox.events")
        );

        log.info(">>> [Service] Search completed. Total Hits: {}", searchHits.getTotalHits());

        // 3. 응답 객체로 변환
        List<ProductSearchItem> items = searchHits.getSearchHits().stream()
            .map(this::convertToSearchItem)
            .collect(Collectors.toList());

        long totalCount = searchHits.getTotalHits();
        int totalPages = (int) Math.ceil((double) totalCount / request.getSize());

        return ProductSearchResponse.builder()
            .items(items)
            .totalCount(totalCount)
            .currentPage(request.getPage())
            .totalPages(totalPages)
            .hasNext(request.getPage() + 1 < totalPages)
            .build();
    }

    private NativeQuery buildSearchQuery(ProductSearchRequest request) {

        // BoolQuery 빌더
        BoolQuery.Builder boolQueryBuilder = new BoolQuery.Builder();

        // 0. 대표 SKU 필터 (기본값: True)
        // 상품 목록에는 대표 SKU만 표시
        boolQueryBuilder.filter(f -> f.term(t -> t.field("isPrimarySku").value(true)));

        // 0.5 카테고리 필터
        if (request.getCategoryId() != null) {
            boolQueryBuilder.filter(f -> f.term(t -> t.field("categoryId").value(request.getCategoryId())));
        }

        // 1. 키워드 검색 (상품명, 옵션명)
        if (request.getKeyword() != null && !request.getKeyword().isBlank()) {
            String keyword = request.getKeyword();

            // 다중 필드 검색 쿼리
            Query multiMatchQuery = Query.of(q -> q.multiMatch(mm -> mm
                .query(keyword)
                .fields("productName^2.0", "optionName") 
            ));

            boolQueryBuilder.must(multiMatchQuery);
        }

        // 2. 가격 범위 필터 (단순 'price' 필드)
        if (request.getMinPrice() != null || request.getMaxPrice() != null) {
            Query priceRangeQuery = Query.of(q -> q.range(r -> r
                .number(n -> {
                    n.field("price");
                    if (request.getMinPrice() != null) {
                        n.gte(request.getMinPrice().doubleValue());
                    }
                    if (request.getMaxPrice() != null) {
                        n.lte(request.getMaxPrice().doubleValue());
                    }
                    return n;
                })
            ));

            boolQueryBuilder.filter(priceRangeQuery);
        }

        // 3. 재고 필터
        if (request.getInStockOnly() != null && request.getInStockOnly()) {
            Query stockQuery = Query.of(q -> q.range(r -> r
                .number(n -> n
                    .field("stockQuantity")
                    .gt(0.0)
                )
            ));

            boolQueryBuilder.filter(stockQuery);
        }

        // 4. 상태 필터 (ACTIVE만)
        Query statusQuery = Query.of(q -> q.term(t -> t
            .field("status")
            .value("ACTIVE")
        ));
        boolQueryBuilder.filter(statusQuery);

        // 5. 최종 쿼리 생성
        Query finalQuery = Query.of(q -> q.bool(boolQueryBuilder.build()));

        // 6. 페이징
        Pageable pageable = PageRequest.of(
            request.getPage() != null ? request.getPage() : 0,
            request.getSize() != null ? request.getSize() : 20
        );

        // 7. NativeQuery 빌더
        NativeQueryBuilder queryBuilder = NativeQuery.builder()
            .withQuery(finalQuery)
            .withPageable(pageable);

        // 8. 정렬 ('minPrice' 대신 'price' 사용)
        String sortBy = request.getSortBy() != null ? request.getSortBy() : "skuId";
        if ("minPrice".equals(sortBy)) sortBy = "price"; // 레거시 파라미터 매핑 필요 시 사용

        String finalSortBy = sortBy;
        SortOrder sortOrder = "asc".equalsIgnoreCase(request.getSortOrder())
            ? SortOrder.Asc
            : SortOrder.Desc;

        queryBuilder.withSort(s -> s.field(f -> f.field(finalSortBy).order(sortOrder)));

        return queryBuilder.build();
    }

    private ProductSearchItem convertToSearchItem(SearchHit<Map> hit) {
        Map<String, Object> source = hit.getContent();

        return ProductSearchItem.builder()
            .id(getLongValue(source, "productId"))
            .skuId(getLongValue(source, "skuId"))
            .name(getStringValue(source, "productName"))
            .optionName(getStringValue(source, "optionName"))
            .description(getStringValue(source, "productName")) // SKU 인덱스에 description 필드가 없을 수 있어 상품명을 대신 사용
            .imageUrl(getStringValue(source, "imageUrl"))
            .price(getLongValue(source, "price"))
            .totalStock(getIntegerValue(source, "stockQuantity"))
            .status(getStringValue(source, "status"))
            .createdAt(getLocalDateTimeValue(source, "createdAt"))
            .updatedAt(getLocalDateTimeValue(source, "updatedAt"))
            .build();
    }

    private String getStringValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    private Long getLongValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return null;
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            log.warn("Failed to parse Long value for key: {}, value: {}", key, value);
            return null;
        }
    }

    private Integer getIntegerValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return null;
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            log.warn("Failed to parse Integer value for key: {}, value: {}", key, value);
            return null;
        }
    }

    private LocalDateTime getLocalDateTimeValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return null;
        try {
            return LocalDateTime.parse(value.toString());
        } catch (Exception e) {
            log.warn("Failed to parse LocalDateTime for key: {}, value: {}", key, value);
            return null;
        }
    }
}
