package com.pawbridge.storeservice.domain.mypage.dto;

import com.pawbridge.storeservice.domain.product.entity.ProductSKU;
import com.pawbridge.storeservice.domain.product.entity.SKUValue;
import com.pawbridge.storeservice.domain.product.entity.Wishlist;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 찜 목록 응답 DTO
 * - 마이페이지용
 * - SKU 기반 (가격, 옵션 정보 포함)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WishlistResponse {

    private Long wishlistId;
    private Long skuId;
    private String skuCode;
    private Long productId;
    private String productName;
    private String productDescription;
    private String productImageUrl;
    private String productStatus;
    private Long price;
    private Map<String, String> options;
    private Long categoryId;
    private String categoryName;
    private LocalDateTime createdAt;

    public static WishlistResponse from(Wishlist wishlist) {
        ProductSKU sku = wishlist.getSku();
        
        // 옵션 정보 추출
        Map<String, String> optionMap = new HashMap<>();
        for (SKUValue skuValue : sku.getSkuValues()) {
            optionMap.put(
                skuValue.getOptionValue().getOptionGroup().getName(),
                skuValue.getOptionValue().getName()
            );
        }
        
        return WishlistResponse.builder()
                .wishlistId(wishlist.getId())
                .skuId(sku.getId())
                .skuCode(sku.getSkuCode())
                .productId(sku.getProduct().getId())
                .productName(sku.getProduct().getName())
                .productDescription(sku.getProduct().getDescription())
                .productImageUrl(sku.getProduct().getImageUrl())
                .productStatus(sku.getProduct().getStatus().name())
                .price(sku.getPrice())
                .options(optionMap)
                .categoryId(sku.getProduct().getCategory() != null ?
                        sku.getProduct().getCategory().getId() : null)
                .categoryName(sku.getProduct().getCategory() != null ?
                        sku.getProduct().getCategory().getName() : null)
                .createdAt(wishlist.getCreatedAt())
                .build();
    }
}
