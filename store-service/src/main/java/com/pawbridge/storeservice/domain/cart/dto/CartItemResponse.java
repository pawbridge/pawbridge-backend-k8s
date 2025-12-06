package com.pawbridge.storeservice.domain.cart.dto;

import com.pawbridge.storeservice.domain.cart.entity.CartItem;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class CartItemResponse {
    private Long id;
    private Long skuId;
    private String skuCode;
    private String productName;
    private Long price;
    private Integer quantity;
    private Long totalPrice;

    public static CartItemResponse from(CartItem item) {
        return CartItemResponse.builder()
                .id(item.getId())
                .skuId(item.getProductSKU().getId())
                .skuCode(item.getProductSKU().getSkuCode())
                .productName(item.getProductSKU().getProduct().getName()) // N+1 주의 (Fetch Join 권장)
                .price(item.getProductSKU().getPrice())
                .quantity(item.getQuantity())
                .totalPrice(item.getProductSKU().getPrice() * item.getQuantity())
                .build();
    }
}
