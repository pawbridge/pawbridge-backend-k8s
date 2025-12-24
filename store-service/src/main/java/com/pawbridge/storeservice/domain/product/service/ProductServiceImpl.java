package com.pawbridge.storeservice.domain.product.service;

import com.pawbridge.storeservice.domain.cart.repository.CartItemRepository;
import com.pawbridge.storeservice.domain.product.dto.SkuUpdateDto;
import com.pawbridge.storeservice.domain.product.dto.ProductCreateRequest;
import com.pawbridge.storeservice.domain.product.dto.ProductResponse;
import com.pawbridge.storeservice.domain.product.entity.*;
import com.pawbridge.storeservice.domain.product.repository.CategoryRepository;
import com.pawbridge.storeservice.domain.product.repository.ProductRepository;
import com.pawbridge.storeservice.domain.product.repository.ProductSKURepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.pawbridge.storeservice.domain.product.dto.ProductDetailResponse;

import java.util.List;
import java.util.Map;


@Slf4j
@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    // Repository 의존성 (4개)
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ProductSKURepository productSKURepository;
    private final CartItemRepository cartItemRepository;
    
    // 분리된 서비스 의존성 (4개)
    private final ProductOptionService optionService;
    private final ProductSKUService skuService;
    private final ProductOutboxService outboxService;
    private final ProductCacheService cacheService;

    @Override
    @Transactional
    public ProductResponse createProduct(ProductCreateRequest request) {
        // 1. 상품 저장
        Product product = Product.builder()
                .name(request.getName())
                .description(request.getDescription())
                .imageUrl(request.getImageUrl())
                .status(ProductStatus.ACTIVE)
                .build();

        if (request.getCategoryId() != null) {
            Category category = categoryRepository.findById(request.getCategoryId())
                    .orElseThrow(() -> new IllegalArgumentException("Category not found: " + request.getCategoryId()));
            product.assignCategory(category);
        }

        product = productRepository.save(product);

        // 2. 옵션 그룹 및 옵션 값 저장 (위임)
        Map<String, OptionValue> optionValueMap = optionService.createOptions(
                product, request.getOptionGroups());

        // 3. SKU 저장 및 옵션 값과 연결 (위임)
        List<ProductSKU> savedSkus = skuService.createSkus(
                product, request.getSkus(), optionValueMap);

        ProductResponse response = ProductResponse.from(product);

        // 4. Outbox 이벤트 생성
        if (!savedSkus.isEmpty()) {
            ProductSKU primarySku = skuService.findPrimarySku(savedSkus);
            for (ProductSKU sku : savedSkus) {
                boolean isPrimary = (sku == primarySku);
                outboxService.publishSkuEvent(product, sku, isPrimary);
            }
        }
        
        return response;
    }

    @Override
    @Transactional(readOnly = true)
    public ProductDetailResponse getProductDetails(Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("Product not found: " + productId));
        return ProductDetailResponse.from(product);
    }

    @Override
    @Transactional
    public ProductResponse updateProduct(Long productId, com.pawbridge.storeservice.domain.product.dto.ProductUpdateRequest request) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("Product not found: " + productId));

        // Dirty Checking
        if (request.getName() != null) product.updateName(request.getName());
        if (request.getDescription() != null) product.updateDescription(request.getDescription());
        if (request.getImageUrl() != null) product.updateImageUrl(request.getImageUrl());
        if (request.getStatus() != null) product.updateStatus(request.getStatus());

        if (request.getCategoryId() != null) {
            Category category = categoryRepository.findById(request.getCategoryId())
                    .orElseThrow(() -> new IllegalArgumentException("Category not found: " + request.getCategoryId()));
            product.assignCategory(category);
        }

        // 특정 SKU 업데이트
        if (request.getSkus() != null && !request.getSkus().isEmpty()) {
            for (SkuUpdateDto skuDto : request.getSkus()) {
               if (skuDto.getId() == null) continue;
               
               product.getSkus().stream()
                   .filter(sku -> sku.getId().equals(skuDto.getId()))
                   .findFirst()
                   .ifPresent(sku -> {
                       if (skuDto.getPrice() != null) sku.updatePrice(skuDto.getPrice());
                       if (skuDto.getStockQuantity() != null) sku.updateStock(skuDto.getStockQuantity());
                   });
            }
        }
        
        // Elasticsearch 동기화를 위해 모든 SKU에 대한 Outbox 이벤트 발행
        if (!product.getSkus().isEmpty()) {
            for (ProductSKU sku : product.getSkus()) {
                outboxService.publishSkuEvent(product, sku, false);
            }
        }
        
        // Cache 무효화
        cacheService.evictProductCache(productId);

        return ProductResponse.from(product);
    }

    @Override
    @Transactional
    public void decreaseStock(Long skuId, int quantity) {
        ProductSKU sku = productSKURepository.findById(skuId)
                .orElseThrow(() -> new IllegalArgumentException("SKU not found: " + skuId));
        sku.decreaseStock(quantity);
        outboxService.publishSkuEvent(sku.getProduct(), sku, false);
        cacheService.evictProductCache(sku.getProduct().getId());
    }

    @Override
    @Transactional
    public void increaseStock(Long skuId, int quantity) {
        ProductSKU sku = productSKURepository.findById(skuId)
                .orElseThrow(() -> new IllegalArgumentException("SKU not found: " + skuId));
        
        sku.increaseStock(quantity);
        productSKURepository.save(sku);
        outboxService.publishSkuEvent(sku.getProduct(), sku, false);
        cacheService.evictProductCache(sku.getProduct().getId());
    }

    @Override
    @Transactional
    public void deleteProduct(Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("Product not found: " + productId));
        
        // 장바구니에 담긴 상품인지 확인
        List<Long> skuIds = product.getSkus().stream()
                .map(ProductSKU::getId)
                .toList();
        if (!skuIds.isEmpty() && cartItemRepository.existsByProductSkuIdIn(skuIds)) {
            throw new IllegalStateException("장바구니에 담긴 상품은 삭제할 수 없습니다. 상품 ID: " + productId);
        }
        
        // SKU 삭제 이벤트 발행 및 SKUValue 삭제 (위임)
        for (ProductSKU sku : product.getSkus()) {
            outboxService.publishSkuDeleteEvent(sku.getId());
            skuService.deleteSkuValues(sku);
        }
        
        // 상품 삭제 (CASCADE로 SKU, OptionGroup 등 함께 삭제)
        productRepository.delete(product);
        
        // 캐시 무효화
        cacheService.evictProductCache(productId);
        
        log.info(">>> [PRODUCT] 상품 삭제 완료: productId={}", productId);
    }
}