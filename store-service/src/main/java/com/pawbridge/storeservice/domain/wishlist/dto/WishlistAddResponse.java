package com.pawbridge.storeservice.domain.wishlist.dto;

import com.pawbridge.storeservice.domain.product.entity.ProductSKU;
import com.pawbridge.storeservice.domain.product.entity.SKUValue;
import com.pawbridge.storeservice.domain.product.entity.Wishlist;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Getter
@Builder
public class WishlistAddResponse {
    private Long wishlistId;
    private Long userId;
    private Long skuId;
    private String skuCode;
    private Long productId;
    private String productName;
    private String productImageUrl;
    private Long price;
    private Map<String, String> options;
    private LocalDateTime createdAt;

    public static WishlistAddResponse from(Wishlist wishlist) {
        ProductSKU sku = wishlist.getSku();
        
        // 옵션 정보 추출
        Map<String, String> optionMap = new HashMap<>();
        for (SKUValue skuValue : sku.getSkuValues()) {
            optionMap.put(
                skuValue.getOptionValue().getOptionGroup().getName(),
                skuValue.getOptionValue().getName()
            );
        }
        
        return WishlistAddResponse.builder()
                .wishlistId(wishlist.getId())
                .userId(wishlist.getUserId())
                .skuId(sku.getId())
                .skuCode(sku.getSkuCode())
                .productId(sku.getProduct().getId())
                .productName(sku.getProduct().getName())
                .productImageUrl(sku.getProduct().getImageUrl())
                .price(sku.getPrice())
                .options(optionMap)
                .createdAt(wishlist.getCreatedAt())
                .build();
    }
}
