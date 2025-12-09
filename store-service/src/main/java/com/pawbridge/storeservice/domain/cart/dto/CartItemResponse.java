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

    public static CartItemResponse of(com.pawbridge.storeservice.domain.product.entity.ProductSKU sku, int quantity) {
        return CartItemResponse.builder()
                .id(null) // Redis Based Cart doesn't have a generated Item ID, or we use SkuId as ID
                .skuId(sku.getId())
                .skuCode(sku.getSkuCode())
                .productName(sku.getProduct().getName())
                .price(sku.getPrice())
                .quantity(quantity)
                .totalPrice(sku.getPrice() * quantity)
                .build();
    }
}
