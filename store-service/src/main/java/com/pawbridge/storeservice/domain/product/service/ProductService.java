package com.pawbridge.storeservice.domain.product.service;

import com.pawbridge.storeservice.domain.product.dto.ProductCreateRequest;
import com.pawbridge.storeservice.domain.product.dto.ProductResponse;

import com.pawbridge.storeservice.domain.product.dto.ProductDetailResponse;

public interface ProductService {
    ProductResponse createProduct(ProductCreateRequest request);
    ProductDetailResponse getProductDetails(Long productId);
    ProductResponse updateProduct(Long productId, com.pawbridge.storeservice.domain.product.dto.ProductUpdateRequest request);
    void decreaseStock(Long skuId, int quantity);
}
