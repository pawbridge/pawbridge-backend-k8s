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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;


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
            outboxService.publishProductSnapshot(product);
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
        Product product = productRepository.findByIdWithLock(productId)
                .orElseThrow(() -> new IllegalArgumentException("Product not found: " + productId));
        productSKURepository.findAllByProductIdWithLock(productId);

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
            outboxService.publishProductSnapshot(product);
        }
        
        // Cache 무효화
        cacheService.evictProductCache(productId);

        return ProductResponse.from(product);
    }

    @Override
    @Transactional
    public void decreaseStocks(Map<Long, Integer> quantitiesBySkuId) {
        adjustStocks(quantitiesBySkuId, true);
    }

    @Override
    @Transactional
    public void increaseStocks(Map<Long, Integer> quantitiesBySkuId) {
        adjustStocks(quantitiesBySkuId, false);
    }

    @Override
    @Transactional
    public void deleteProduct(Long productId) {
        Product product = productRepository.findByIdWithLock(productId)
                .orElseThrow(() -> new IllegalArgumentException("Product not found: " + productId));
        productSKURepository.findAllByProductIdWithLock(productId);
        
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
        outboxService.publishProductSnapshot(product);
        
        // 캐시 무효화
        cacheService.evictProductCache(productId);
        
        log.info(">>> [PRODUCT] 상품 소프트 삭제 완료: productId={}, status=DELETED", productId);
    }

    private void adjustStocks(Map<Long, Integer> quantitiesBySkuId, boolean decrease) {
        if (quantitiesBySkuId == null || quantitiesBySkuId.isEmpty()) {
            return;
        }

        quantitiesBySkuId.forEach((skuId, quantity) -> {
            if (skuId == null || quantity == null || quantity <= 0) {
                throw new IllegalArgumentException("SKU ID와 수량은 유효한 양수여야 합니다.");
            }
        });

        List<ProductSKURepository.SkuProductId> skuProductIds = productSKURepository
                .findProductIdsBySkuIdIn(quantitiesBySkuId.keySet());
        if (skuProductIds.size() != quantitiesBySkuId.size()) {
            throw new IllegalArgumentException("존재하지 않는 SKU가 포함되어 있습니다.");
        }

        Map<Long, List<Long>> skuIdsByProductId = new HashMap<>();
        skuProductIds.forEach(reference -> skuIdsByProductId
                .computeIfAbsent(reference.getProductId(), ignored -> new ArrayList<>())
                .add(reference.getSkuId()));

        skuIdsByProductId.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> adjustProductStocks(entry.getKey(), entry.getValue(), quantitiesBySkuId, decrease));
    }

    private void adjustProductStocks(
            Long productId,
            List<Long> targetSkuIds,
            Map<Long, Integer> quantitiesBySkuId,
            boolean decrease
    ) {
        Product product = productRepository.findByIdWithLock(productId)
                .orElseThrow(() -> new IllegalArgumentException("Product not found: " + productId));
        List<ProductSKU> lockedSkus = productSKURepository.findAllByProductIdWithLock(productId);
        Map<Long, ProductSKU> lockedSkuById = lockedSkus.stream()
                .collect(Collectors.toMap(ProductSKU::getId, sku -> sku));

        targetSkuIds.stream()
                .sorted(Comparator.naturalOrder())
                .forEach(skuId -> {
                    ProductSKU sku = Optional.ofNullable(lockedSkuById.get(skuId))
                            .orElseThrow(() -> new IllegalArgumentException("SKU not found after product lock: " + skuId));
                    if (decrease && product.getStatus() != ProductStatus.ACTIVE) {
                        throw new IllegalStateException(
                                "주문할 수 없는 상품입니다. 상품 상태: " + product.getStatus() + ", SKU ID: " + skuId
                        );
                    }

                    if (decrease) {
                        sku.decreaseStock(quantitiesBySkuId.get(skuId));
                    } else {
                        sku.increaseStock(quantitiesBySkuId.get(skuId));
                    }
                });

        outboxService.publishProductSnapshot(product);
        cacheService.evictProductCache(productId);
    }
}
