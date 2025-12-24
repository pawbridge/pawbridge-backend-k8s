package com.pawbridge.storeservice.domain.wishlist.dto;

import com.pawbridge.storeservice.domain.product.entity.Wishlist;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class WishlistAddResponse {
    private Long wishlistId;
    private Long userId;
    private Long productId;
    private String productName;
    private LocalDateTime createdAt;

    public static WishlistAddResponse from(Wishlist wishlist) {
        return WishlistAddResponse.builder()
                .wishlistId(wishlist.getId())
                .userId(wishlist.getUserId())
                .productId(wishlist.getProduct().getId())
                .productName(wishlist.getProduct().getName())
                .createdAt(wishlist.getCreatedAt())
                .build();
    }
}
