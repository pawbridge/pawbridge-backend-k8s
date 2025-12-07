package com.pawbridge.storeservice.domain.product.controller;

import com.pawbridge.storeservice.domain.product.dto.ProductCreateRequest;
import com.pawbridge.storeservice.domain.product.dto.ProductResponse;
import com.pawbridge.storeservice.domain.product.dto.ProductSearchRequest;
import com.pawbridge.storeservice.domain.product.dto.ProductSearchResponse;
import com.pawbridge.storeservice.domain.product.service.ProductSearchService;
import com.pawbridge.storeservice.domain.product.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;
    private final ProductSearchService productSearchService;

    @PostMapping
    public ResponseEntity<ProductResponse> createProduct(@RequestBody ProductCreateRequest request) {
        ProductResponse response = productService.createProduct(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<ProductSearchResponse> searchProducts(
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) Long minPrice,
        @RequestParam(required = false) Long maxPrice,
        @RequestParam(required = false, defaultValue = "false") Boolean inStockOnly,
        @RequestParam(required = false) String sortBy,
        @RequestParam(required = false, defaultValue = "desc") String sortOrder,
        @RequestParam(required = false, defaultValue = "0") Integer page,
        @RequestParam(required = false, defaultValue = "20") Integer size
    ) {
        // [DEBUG] Request Log
        org.slf4j.LoggerFactory.getLogger(ProductController.class).info(">>> [Controller] Search Request - Keyword: {}, MinPrice: {}, InStock: {}", keyword, minPrice, inStockOnly);

        ProductSearchRequest searchRequest = ProductSearchRequest.builder()
            .keyword(keyword)
            .minPrice(minPrice)
            .maxPrice(maxPrice)
            .inStockOnly(inStockOnly)
            .sortBy(sortBy)
            .sortOrder(sortOrder)
            .page(page)
            .size(size)
            .build();

        ProductSearchResponse response = productSearchService.searchProducts(searchRequest);
        return ResponseEntity.ok(response);
    }
}
