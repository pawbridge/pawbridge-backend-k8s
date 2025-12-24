package com.pawbridge.storeservice.domain.product.service;

import com.pawbridge.storeservice.domain.product.dto.SkuCreateDto;
import com.pawbridge.storeservice.domain.product.entity.OptionValue;
import com.pawbridge.storeservice.domain.product.entity.Product;
import com.pawbridge.storeservice.domain.product.entity.ProductSKU;
import com.pawbridge.storeservice.domain.product.entity.SKUValue;
import com.pawbridge.storeservice.domain.product.repository.ProductSKURepository;
import com.pawbridge.storeservice.domain.product.repository.SKUValueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Product SKU Service
 * - SKU 및 SKU-옵션값 연결 관리
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductSKUService {

    private final ProductSKURepository productSKURepository;
    private final SKUValueRepository skuValueRepository;

    /**
     * SKU 생성 및 옵션 값과 연결
     * @param product 상품 엔티티
     * @param skuDtos SKU 생성 DTO 목록
     * @param optionValueMap "그룹명:값명" -> OptionValue 맵
     * @return 생성된 SKU 목록
     */
    public List<ProductSKU> createSkus(Product product, List<SkuCreateDto> skuDtos, 
                                        Map<String, OptionValue> optionValueMap) {
        List<ProductSKU> savedSkus = new ArrayList<>();
        
        if (skuDtos == null || skuDtos.isEmpty()) {
            return savedSkus;
        }
        
        for (SkuCreateDto skuDto : skuDtos) {
            // SKU 저장
            ProductSKU sku = ProductSKU.builder()
                    .product(product)
                    .skuCode(skuDto.getSkuCode())
                    .price(skuDto.getPrice())
                    .stockQuantity(skuDto.getStockQuantity())
                    .build();
            productSKURepository.save(sku);
            
            savedSkus.add(sku);
            product.getSkus().add(sku);

            // 옵션 연결 (SKUValue)
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
                    sku.getSkuValues().add(skuValue);
                }
            }
        }
        
        log.debug(">>> [SKU] SKU 생성 완료: productId={}, SKU 수={}", 
                product.getId(), savedSkus.size());
        
        return savedSkus;
    }

    /**
     * 대표 SKU 찾기 (최저가 → 동일 시 낮은 ID)
     */
    public ProductSKU findPrimarySku(List<ProductSKU> skus) {
        if (skus == null || skus.isEmpty()) {
            return null;
        }
        
        return skus.stream()
                .min((s1, s2) -> {
                    int priceCompare = s1.getPrice().compareTo(s2.getPrice());
                    if (priceCompare != 0) return priceCompare;
                    if (s1.getId() == null || s2.getId() == null) return 0;
                    return s1.getId().compareTo(s2.getId());
                })
                .orElse(skus.get(0));
    }

    /**
     * SKU의 SKUValue 삭제 (상품 삭제 전 호출)
     */
    public void deleteSkuValues(ProductSKU sku) {
        skuValueRepository.deleteAll(sku.getSkuValues());
        sku.getSkuValues().clear();
    }
}
