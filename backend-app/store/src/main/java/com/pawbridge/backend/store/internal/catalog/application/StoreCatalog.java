package com.pawbridge.backend.store.internal.catalog.application;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Read-only Store catalog contracts, retaining the existing Store JSON field names. */
public interface StoreCatalog {
    record Search(String keyword, Long minPrice, Long maxPrice, Long categoryId, Boolean inStockOnly,
                  String sortBy, String sortOrder, Integer page, Integer size) { }
    record Item(Long id, Long skuId, String name, String optionName, String description, String imageUrl,
                Long price, Integer totalStock, String status,
                @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime createdAt,
                @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime updatedAt) { }
    record SearchResult(List<Item> items, Long totalCount, Integer currentPage, Integer totalPages,
                        Boolean hasNext) { }
    record Sku(Long skuId, String skuCode, Long price, Integer stockQuantity, Map<String, String> options) { }
    record Product(Long productId, String name, String description, String imageUrl, String status,
                   Long categoryId, String categoryName, Long viewCount,
                   @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime createdAt,
                   @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime updatedAt, List<Sku> skus) { }
    record Category(Long id, String name, String description, Long parentId, List<Category> children) { }
    record OptionValue(Long id, String name, Long groupId, String groupName) { }
    record OptionGroup(Long id, String name, List<OptionValue> values) { }

    SearchResult search(Search request);
    Product product(long productId);
    List<Category> categories();
    List<OptionGroup> optionGroups();
    OptionGroup optionGroup(long groupId);
}
