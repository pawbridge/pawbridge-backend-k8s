package com.pawbridge.storeservice.domain.mypage.dto;

import com.pawbridge.storeservice.domain.product.entity.Wishlist;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 찜 목록 응답 DTO
 * - 마이페이지용
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WishlistResponse {

    private Long wishlistId;
    private Long productId;
    private String productName;
    private String productDescription;
    private String productImageUrl;
    private String productStatus;
    private Long categoryId;
    private String categoryName;
    private LocalDateTime createdAt;

    public static WishlistResponse from(Wishlist wishlist) {
        return WishlistResponse.builder()
                .wishlistId(wishlist.getId())
                .productId(wishlist.getProduct().getId())
                .productName(wishlist.getProduct().getName())
                .productDescription(wishlist.getProduct().getDescription())
                .productImageUrl(wishlist.getProduct().getImageUrl())
                .productStatus(wishlist.getProduct().getStatus().name())
                .categoryId(wishlist.getProduct().getCategory() != null ?
                        wishlist.getProduct().getCategory().getId() : null)
                .categoryName(wishlist.getProduct().getCategory() != null ?
                        wishlist.getProduct().getCategory().getName() : null)
                .createdAt(wishlist.getCreatedAt())
                .build();
    }
}
