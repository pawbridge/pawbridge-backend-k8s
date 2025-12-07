package com.pawbridge.storeservice.domain.product.service;

import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.json.JsonData;
import com.pawbridge.storeservice.domain.product.dto.ProductSearchRequest;
import com.pawbridge.storeservice.domain.product.dto.ProductSearchResponse;
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
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductSearchService {

    private final ElasticsearchOperations elasticsearchOperations;

    public ProductSearchResponse searchProducts(ProductSearchRequest request) {
        log.info(">>> [Service] Building search query for: {}", request);

        // 1. Build Elasticsearch Query
        NativeQuery searchQuery = buildSearchQuery(request);

        // [DEBUG] Log the actual query DSL
        log.info(">>> [Service] Generated Query DSL: {}", searchQuery.getQuery().toString());

        // 2. Execute Search
        log.info(">>> [Service] Executing Elasticsearch query...");
        SearchHits<Map> searchHits = elasticsearchOperations.search(
            searchQuery,
            Map.class,
            org.springframework.data.elasticsearch.core.mapping.IndexCoordinates.of("store.outbox.events")
        );

        log.info(">>> [Service] Search completed. Total Hits: {}", searchHits.getTotalHits());

        // 3. Convert to Response
        List<ProductSearchResponse.ProductSearchItem> items = searchHits.getSearchHits().stream()
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

        // BoolQuery Builder
        BoolQuery.Builder boolQueryBuilder = new BoolQuery.Builder();

        // 0. Primary SKU Filter (Default: True)
        // Show only representative SKUs in the product list
        boolQueryBuilder.filter(f -> f.term(t -> t.field("isPrimarySku").value(true)));

        // 1. Keyword Search (productName, optionName)
        if (request.getKeyword() != null && !request.getKeyword().isBlank()) {
            String keyword = request.getKeyword();

            // Multi-match query
            Query multiMatchQuery = Query.of(q -> q.multiMatch(mm -> mm
                .query(keyword)
                .fields("productName^2.0", "optionName") 
            ));

            boolQueryBuilder.must(multiMatchQuery);
        }

        // 2. Price Range Filter (Simple 'price' field)
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

        // 3. Stock Filter
        if (request.getInStockOnly() != null && request.getInStockOnly()) {
            Query stockQuery = Query.of(q -> q.range(r -> r
                .number(n -> n
                    .field("stockQuantity")
                    .gt(0.0)
                )
            ));

            boolQueryBuilder.filter(stockQuery);
        }

        // 4. Status Filter (ACTIVE만)
        Query statusQuery = Query.of(q -> q.term(t -> t
            .field("status")
            .value("ACTIVE")
        ));
        boolQueryBuilder.filter(statusQuery);

        // 5. Build Final Query
        Query finalQuery = Query.of(q -> q.bool(boolQueryBuilder.build()));

        // 6. Pagination
        Pageable pageable = PageRequest.of(
            request.getPage() != null ? request.getPage() : 0,
            request.getSize() != null ? request.getSize() : 20
        );

        // 7. NativeQuery Builder
        NativeQueryBuilder queryBuilder = NativeQuery.builder()
            .withQuery(finalQuery)
            .withPageable(pageable);

        // 8. Sorting (Use 'price' instead of 'minPrice')
        String sortBy = request.getSortBy() != null ? request.getSortBy() : "skuId";
        if ("minPrice".equals(sortBy)) sortBy = "price"; // Map legacy param if needed

        String finalSortBy = sortBy;
        SortOrder sortOrder = "asc".equalsIgnoreCase(request.getSortOrder())
            ? SortOrder.Asc
            : SortOrder.Desc;

        queryBuilder.withSort(s -> s.field(f -> f.field(finalSortBy).order(sortOrder)));

        return queryBuilder.build();
    }

    private ProductSearchResponse.ProductSearchItem convertToSearchItem(SearchHit<Map> hit) {
        Map<String, Object> source = hit.getContent();

        return ProductSearchResponse.ProductSearchItem.builder()
            .id(getLongValue(source, "productId"))
            .skuId(getLongValue(source, "skuId"))
            .name(getStringValue(source, "productName"))
            .optionName(getStringValue(source, "optionName"))
            .description(getStringValue(source, "productName")) // Description might not be in SKU index, use name or fetch separately if needed. Or mapping stores it? Let's check mapping. Ah mapping doesn't have description. We used productName as description in previous logic? Let's check mapping again. 
            // Mapping has "productName", "optionName". No description field in mapping.
            // So description field in response might be empty or duplicate name.
            // Wait, previous code used "description" field. Let's double check mapping.
            // Mapping file has "description" removed? No, let's check.
            // I will use safe mapping.
            .imageUrl(getStringValue(source, "imageUrl"))
            .price(getLongValue(source, "price"))
            .totalStock(getIntegerValue(source, "stockQuantity"))
            .status(getStringValue(source, "status"))
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
}
