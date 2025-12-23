package com.pawbridge.storeservice.domain.product.service;

import com.pawbridge.storeservice.domain.product.dto.OptionGroupCreateDto;
import com.pawbridge.storeservice.domain.product.dto.SkuCreateDto;
import com.pawbridge.storeservice.domain.product.dto.SkuUpdateDto;
import com.pawbridge.storeservice.domain.product.dto.ProductCreateRequest;
import com.pawbridge.storeservice.domain.product.dto.ProductUpdateRequest;
import com.pawbridge.storeservice.domain.product.dto.ProductResponse;
import com.pawbridge.storeservice.domain.product.entity.*;
import com.pawbridge.storeservice.domain.product.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.pawbridge.storeservice.domain.product.dto.ProductDetailResponse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


@Slf4j
@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    // Repository 의존성 (6개)
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final OptionGroupRepository optionGroupRepository;
    private final OptionValueRepository optionValueRepository;
    private final ProductSKURepository productSKURepository;
    private final SKUValueRepository skuValueRepository;
    
    // 분리된 서비스 의존성 (2개)
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
                .status(ProductStatus.ACTIVE) // 기본 상태
                .build();

        if (request.getCategoryId() != null) {
            Category category = categoryRepository.findById(request.getCategoryId())
                    .orElseThrow(() -> new IllegalArgumentException("Category not found: " + request.getCategoryId()));
            product.assignCategory(category);
        }

        product = productRepository.save(product);

        // "그룹명:값명"으로 OptionValue 엔티티를 찾기 위한 맵
        Map<String, OptionValue> optionValueMap = new HashMap<>();

        // 2. 옵션 그룹 및 옵션 값 저장
        if (request.getOptionGroups() != null) {
            for (OptionGroupCreateDto groupDto : request.getOptionGroups()) {
                OptionGroup group = OptionGroup.builder()
                        .product(product)
                        .name(groupDto.getName())
                        .build();
                optionGroupRepository.save(group);

                for (String valName : groupDto.getValues()) {
                    OptionValue value = OptionValue.builder()
                            .optionGroup(group)
                            .name(valName)
                            .build();
                    optionValueRepository.save(value);
                    optionValueMap.put(groupDto.getName() + ":" + valName, value);
                }
            }
        }

        // 집계를 위한 변수 (로직이 변경되었지만 안전/미래 사용을 위해 유지)
        List<ProductSKU> tempSavedSkus = new ArrayList<>();

        // 3. SKU 저장 및 옵션 값과 연결
        if (request.getSkus() != null && !request.getSkus().isEmpty()) {
            for (SkuCreateDto skuDto : request.getSkus()) {
                // 3-1. SKU 저장
                ProductSKU sku = ProductSKU.builder()
                        .product(product)
                        .skuCode(skuDto.getSkuCode())
                        .price(skuDto.getPrice())
                        .stockQuantity(skuDto.getStockQuantity())
                        .build();
                productSKURepository.save(sku);
                
                // Outbox 생성을 위해 임시 리스트에 추가
                tempSavedSkus.add(sku);
                // [Fix] Response 생성을 위해 Product 객체의 List에도 추가
                product.getSkus().add(sku);

                // 3-2. 옵션 연결 (SKUValue)
                if (skuDto.getOptions() != null) {
                    for (Map.Entry<String, String> entry : skuDto.getOptions().entrySet()) {
                        String key = entry.getKey() + ":" + entry.getValue();
                        OptionValue optionValue = optionValueMap.get(key);
                        if (optionValue == null) {
                            throw new IllegalArgumentException("SKU 내 유효하지 않은 옵션 값입니다: " + key);
                        }
                        SKUValue skuValue = SKUValue.builder()
                                .productSKU(sku)
                                .optionValue(optionValue)
                                .build();
                        skuValueRepository.save(skuValue);
                        // [Fix] Outbox 생성을 위해 즉시 사용할 수 있도록 메모리 리스트에 추가
                        sku.getSkuValues().add(skuValue);
                    }
                }
            }
        }

        ProductResponse response = ProductResponse.from(product);

        // 4. Outbox 이벤트 생성 (문서당 SKU 하나)
        if (!tempSavedSkus.isEmpty()) {
            // 대표 SKU 찾기 로직: 최저가 -> 동일하면 낮은 ID
            ProductSKU primarySku = tempSavedSkus.stream()
                .min((s1, s2) -> {
                    int priceCompare = s1.getPrice().compareTo(s2.getPrice());
                    if (priceCompare != 0) return priceCompare;
                    if (s1.getId() == null || s2.getId() == null) return 0; 
                    return s1.getId().compareTo(s2.getId());
                })
                .orElse(tempSavedSkus.get(0));

            for (ProductSKU sku : tempSavedSkus) {
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
        
        // DTO 변환 시 지연 로딩(Lazy Loading)으로 SKUs와 OptionValues를 가져옴
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
               
               // 단순 리스트에서 매칭되는 SKU 찾기
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
        
        // SKU 삭제 이벤트 발행 (ES에서 제거하기 위함)
        for (ProductSKU sku : product.getSkus()) {
            outboxService.publishSkuDeleteEvent(sku.getId());
            // SKUValue를 먼저 명시적으로 삭제 (OptionValue 참조 해제)
            skuValueRepository.deleteAll(sku.getSkuValues());
            sku.getSkuValues().clear();
        }
        
        // 상품 삭제 (CASCADE로 SKU, OptionGroup 등 함께 삭제)
        productRepository.delete(product);
        
        // 캐시 무효화
        cacheService.evictProductCache(productId);
        
        log.info(">>> [PRODUCT] 상품 삭제 완료: productId={}", productId);
    }
}