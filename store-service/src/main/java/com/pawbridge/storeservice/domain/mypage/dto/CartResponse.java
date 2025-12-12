package com.pawbridge.storeservice.domain.mypage.dto;

import com.pawbridge.storeservice.domain.cart.entity.Cart;
import com.pawbridge.storeservice.domain.cart.entity.CartItem;
import com.pawbridge.storeservice.domain.product.entity.ProductSKU;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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
        private Long totalPrice;
    }

    /**
     * Cart와 ProductSKU 맵을 받아서 CartResponse 생성
     */
    public static CartResponse from(Cart cart, Map<Long, ProductSKU> skuMap) {
        List<CartItemDto> itemDtos = cart.getItems().stream()
                .map(item -> {
                    ProductSKU sku = skuMap.get(item.getProductSkuId());
                    return CartItemDto.builder()
                            .cartItemId(item.getId())
                            .productSkuId(item.getProductSkuId())
                            .productName(sku != null ? sku.getProduct().getName() : null)
                            .skuCode(sku != null ? sku.getSkuCode() : null)
                            .price(sku != null ? sku.getPrice() : 0L)
                            .quantity(item.getQuantity())
                            .totalPrice(sku != null ? sku.getPrice() * item.getQuantity() : 0L)
                            .build();
                })
                .collect(Collectors.toList());

        return CartResponse.builder()
                .cartId(cart.getId())
                .userId(cart.getUserId())
                .items(itemDtos)
                .createdAt(cart.getCreatedAt())
                .build();
    }
}
