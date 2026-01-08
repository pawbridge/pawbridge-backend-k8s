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


@Slf4j
@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    // Repository 의존성 (4개)
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ProductSKURepository productSKURepository;
    private final CartItemRepository cartItemRepository;
    
    // 분리된 서비스 의존성 (3개 - optionService 제거)
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

        // 2. SKU 저장 및 옵션 값과 연결 (ID 기반)
        List<ProductSKU> savedSkus = skuService.createSkus(product, request.getSkus());

        ProductResponse response = ProductResponse.from(product);

        // 3. Outbox 이벤트 생성
        if (!savedSkus.isEmpty()) {
            ProductSKU primarySku = skuService.findPrimarySku(savedSkus);
            for (ProductSKU sku : savedSkus) {
                boolean isPrimary = (sku == primarySku);
                outboxService.publishSkuEvent(product, sku, isPrimary);
            }
        }
        
        log.info(">>> [PRODUCT] 상품 등록 완료: productId={}, name={}", product.getId(), product.getName());
        
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
        // 비관적 락(PESSIMISTIC_WRITE)으로 재고 동시성 제어 - 트랜잭션 범위 내에서 락 자동 관리
        ProductSKU sku = productSKURepository.findByIdWithLock(skuId)
                .orElseThrow(() -> new IllegalArgumentException("SKU not found: " + skuId));
        
        // 상품 상태 검증 (락 획득 후 검증하여 정확성 보장)
        ProductStatus status = sku.getProduct().getStatus();
        if (status != ProductStatus.ACTIVE) {
            throw new IllegalStateException("주문할 수 없는 상품입니다. 상품 상태: " + status + ", SKU ID: " + skuId);
        }
        
        sku.decreaseStock(quantity);
        outboxService.publishSkuEvent(sku.getProduct(), sku, false);
        cacheService.evictProductCache(sku.getProduct().getId());
    }

    @Override
    @Transactional
    public void increaseStock(Long skuId, int quantity) {
        // 비관적 락(PESSIMISTIC_WRITE)으로 재고 동시성 제어 - 트랜잭션 범위 내에서 락 자동 관리
        ProductSKU sku = productSKURepository.findByIdWithLock(skuId)
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
        
        // 이미 삭제된 상품인지 확인
        if (product.getStatus() == ProductStatus.DELETED) {
            throw new IllegalStateException("이미 삭제된 상품입니다. 상품 ID: " + productId);
        }
        
        // 장바구니에 담긴 상품인지 확인
        List<Long> skuIds = product.getSkus().stream()
                .map(ProductSKU::getId)
                .toList();
        if (!skuIds.isEmpty() && cartItemRepository.existsByProductSkuIdIn(skuIds)) {
            throw new IllegalStateException("장바구니에 담긴 상품은 삭제할 수 없습니다. 상품 ID: " + productId);
        }
        
        // 소프트 삭제: status를 DELETED로 변경
        product.updateStatus(ProductStatus.DELETED);
        
        // Elasticsearch 동기화를 위해 모든 SKU에 대한 Outbox 이벤트 발행
        for (ProductSKU sku : product.getSkus()) {
            outboxService.publishSkuEvent(product, sku, false);
        }
        
        // 캐시 무효화
        cacheService.evictProductCache(productId);
        
        log.info(">>> [PRODUCT] 상품 소프트 삭제 완료: productId={}, status=DELETED", productId);
    }
}