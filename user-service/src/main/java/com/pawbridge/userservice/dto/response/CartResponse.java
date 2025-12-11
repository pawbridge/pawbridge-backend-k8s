package com.pawbridge.userservice.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * store-service에서 받아오는 장바구니 정보 DTO
 * - store-service의 CartResponse와 동일한 구조
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CartResponse {

    private Long cartId;
    private Long userId;
    private List<CartItemDto> items;
    private LocalDateTime createdAt;

    /**
     * 장바구니 항목 DTO (내부 클래스)
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CartItemDto {
        private Long cartItemId;
        private Long productSkuId;
        private String productName;
        private String skuCode;
        private Long price;
        private Integer quantity;
        private Long totalPrice;
    }
}
