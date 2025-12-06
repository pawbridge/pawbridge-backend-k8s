package com.pawbridge.storeservice.domain.product.service;

import com.pawbridge.storeservice.domain.product.dto.ProductCreateRequest;
import com.pawbridge.storeservice.domain.product.dto.ProductResponse;

public interface ProductService {
    ProductResponse createProduct(ProductCreateRequest request);
}
