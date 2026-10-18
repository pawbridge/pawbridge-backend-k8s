package com.pawbridge.storeservice.domain.product.service;

import com.pawbridge.storeservice.domain.product.dto.ProductSearchRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductSearchServiceTest {

    @Mock
    private ElasticsearchOperations elasticsearchOperations;

    @Test
    @SuppressWarnings("unchecked")
    void givenSearchRequest_whenSearching_thenUsesReadAliasAndCanonicalFilters() {
        SearchHits<Map> searchHits = org.mockito.Mockito.mock(SearchHits.class);
        when(searchHits.getSearchHits()).thenReturn(List.of());
        when(searchHits.getTotalHits()).thenReturn(0L);
        when(elasticsearchOperations.search(any(NativeQuery.class), eq(Map.class), any(IndexCoordinates.class)))
                .thenReturn(searchHits);
        ProductSearchService productSearchService = new ProductSearchService(
                elasticsearchOperations,
                "store-products-read"
        );

        productSearchService.searchProducts(ProductSearchRequest.builder().inStockOnly(true).build());

        ArgumentCaptor<NativeQuery> queryCaptor = ArgumentCaptor.forClass(NativeQuery.class);
        ArgumentCaptor<IndexCoordinates> indexCaptor = ArgumentCaptor.forClass(IndexCoordinates.class);
        verify(elasticsearchOperations).search(queryCaptor.capture(), eq(Map.class), indexCaptor.capture());

        assertArrayEquals(new String[]{"store-products-read"}, indexCaptor.getValue().getIndexNames());
        String queryDsl = queryCaptor.getValue().getQuery().toString();
        assertTrue(queryDsl.contains("isPrimarySku"));
        assertTrue(queryDsl.contains("totalStockQuantity"));
        assertTrue(queryDsl.contains("ACTIVE"));
    }
}
