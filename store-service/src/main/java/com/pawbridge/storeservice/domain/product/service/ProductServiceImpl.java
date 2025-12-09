package com.pawbridge.storeservice.domain.product.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.storeservice.common.entity.Outbox;
import com.pawbridge.storeservice.common.repository.OutboxRepository;
import com.pawbridge.storeservice.domain.product.dto.ProductCreateRequest;
import com.pawbridge.storeservice.domain.product.dto.ProductUpdateRequest;
import com.pawbridge.storeservice.domain.product.dto.ProductEventPayload;
import com.pawbridge.storeservice.domain.product.dto.ProductResponse;
import com.pawbridge.storeservice.domain.product.entity.*;
import com.pawbridge.storeservice.domain.product.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.pawbridge.storeservice.domain.product.dto.ProductDetailResponse;
import org.springframework.cache.annotation.Cacheable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final OptionGroupRepository optionGroupRepository;
    private final OptionValueRepository optionValueRepository;
    private final ProductSKURepository productSKURepository;
    private final SKUValueRepository skuValueRepository;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

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
            for (ProductCreateRequest.OptionGroupDto groupDto : request.getOptionGroups()) {
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
            for (ProductCreateRequest.SkuDto skuDto : request.getSkus()) {
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
        // 로컬에 수집된 SKU 사용
        if (!tempSavedSkus.isEmpty()) {
            // 대표 SKU 찾기 로직: 최저가 -> 동일하면 낮은 ID
            ProductSKU primarySku = tempSavedSkus.stream()
                .min((s1, s2) -> {
                    int priceCompare = s1.getPrice().compareTo(s2.getPrice());
                    if (priceCompare != 0) return priceCompare;
                    // 아직 flush가 안 되어 ID가 null일 수? 보통 save() 시점에 ID 할당됨. 안전하게 처리.
                    if (s1.getId() == null || s2.getId() == null) return 0; 
                    return s1.getId().compareTo(s2.getId());
                })
                .orElse(tempSavedSkus.get(0));

            for (ProductSKU sku : tempSavedSkus) {
                boolean isPrimary = (sku == primarySku); // 같은 리스트 인스턴스를 사용하므로 객체 식별자 비교 안전함
                
                ProductEventPayload eventPayload = ProductEventPayload.builder()
                        .skuId(sku.getId())
                        .productId(product.getId())
                        .productName(product.getName())
                        .skuCode(sku.getSkuCode())
                        // [Fix] 옵션명 생성 (예: "Color: Red, Size: L")
                        .optionName(sku.generateOptionName()) 
                        .price(sku.getPrice())
                        .stockQuantity(sku.getStockQuantity())
                        .isPrimarySku(isPrimary)
                        .status(product.getStatus().name())
                        .imageUrl(product.getImageUrl())
                        .createdAt(product.getCreatedAt())
                        .updatedAt(product.getUpdatedAt())
                        .build();

                try {
                    String payload = objectMapper.writeValueAsString(eventPayload);
                    Outbox outbox = Outbox.builder()
                            .aggregateType("PRODUCT_SKU")
                            .aggregateId(String.valueOf(sku.getId()))
                            .eventType("SKU_UPDATED")
                            .payload(payload)
                            .build();
                    outboxRepository.save(outbox);
                } catch (JsonProcessingException e) {
                    log.error("Failed to serialize SKU event payload", e);
                    throw new RuntimeException("Failed to create outbox event", e);
                }
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

        // Update Specific SKUs
        if (request.getSkus() != null && !request.getSkus().isEmpty()) {
            for (ProductUpdateRequest.SkuUpdateDto skuDto : request.getSkus()) {
               if (skuDto.getId() == null) continue;
               
               // Find matching SKU in simple List (O(N) is fine for typically <100 SKUs)
               product.getSkus().stream()
                   .filter(sku -> sku.getId().equals(skuDto.getId()))
                   .findFirst()
                   .ifPresent(sku -> {
                       if (skuDto.getPrice() != null) sku.updatePrice(skuDto.getPrice());
                       if (skuDto.getStockQuantity() != null) sku.updateStock(skuDto.getStockQuantity());
                       // skuCode update is rare but possible
                   });
            }
        }
        
        // Publish Outbox Event for all SKUs to sync with Elasticsearch
        if (!product.getSkus().isEmpty()) {
            for (ProductSKU sku : product.getSkus()) {
                ProductEventPayload eventPayload = ProductEventPayload.builder()
                        .skuId(sku.getId())
                        .productId(product.getId())
                        .productName(product.getName())
                        .skuCode(sku.getSkuCode())
                        .optionName(sku.generateOptionName())
                        .price(sku.getPrice())
                        .stockQuantity(sku.getStockQuantity())
                        .isPrimarySku(false) // Simplified for bulk update
                        .status(product.getStatus().name())
                        .imageUrl(product.getImageUrl())
                        .createdAt(product.getCreatedAt())
                        .updatedAt(product.getUpdatedAt())
                        .build();

                try {
                    String payload = objectMapper.writeValueAsString(eventPayload);
                    Outbox outbox = Outbox.builder()
                            .aggregateType("PRODUCT_SKU")
                            .aggregateId(String.valueOf(sku.getId()))
                            .eventType("SKU_UPDATED")
                            .payload(payload)
                            .build();
                    outboxRepository.save(outbox);
                } catch (JsonProcessingException e) {
                    log.error("Failed to create outbox event for sku update", e);
                }
            }
        }
        
        return ProductResponse.from(product);
    }

    @Override
    @Transactional
    public void decreaseStock(Long skuId, int quantity) {
        ProductSKU sku = productSKURepository.findById(skuId)
                .orElseThrow(() -> new IllegalArgumentException("SKU not found: " + skuId));
        sku.decreaseStock(quantity);
    }
}