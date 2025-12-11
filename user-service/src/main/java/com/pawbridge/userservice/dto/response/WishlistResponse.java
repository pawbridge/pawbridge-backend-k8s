package com.pawbridge.userservice.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * store-service에서 받아오는 찜 정보 DTO
 * - store-service의 WishlistResponse와 동일한 구조
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
}
