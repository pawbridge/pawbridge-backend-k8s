package com.pawbridge.storeservice.domain.mypage.dto;

import com.pawbridge.storeservice.domain.cart.entity.Cart;
import com.pawbridge.storeservice.domain.cart.entity.CartItem;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 장바구니 응답 DTO
 * - 마이페이지용
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
        private Long totalPrice;  // price * quantity
    }

    public static CartResponse from(Cart cart) {
        List<CartItemDto> itemDtos = cart.getCartItems().stream()
                .map(item -> CartItemDto.builder()
                        .cartItemId(item.getId())
                        .productSkuId(item.getProductSKU().getId())
                        .productName(item.getProductSKU().getProduct().getName())
                        .skuCode(item.getProductSKU().getSkuCode())
                        .price(item.getProductSKU().getPrice())
                        .quantity(item.getQuantity())
                        .totalPrice(item.getProductSKU().getPrice() * item.getQuantity())
                        .build())
                .collect(Collectors.toList());

        return CartResponse.builder()
                .cartId(cart.getId())
                .userId(cart.getUserId())
                .items(itemDtos)
                .createdAt(cart.getCreatedAt())
                .build();
    }
}
