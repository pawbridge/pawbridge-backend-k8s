package com.pawbridge.storeservice.domain.product.service;
import com.pawbridge.storeservice.domain.product.dto.ProductSearchRequest;
import com.pawbridge.storeservice.domain.product.dto.ProductSearchResponse;
public interface ProductSearch {
    ProductSearchResponse searchProducts(ProductSearchRequest request);
}
