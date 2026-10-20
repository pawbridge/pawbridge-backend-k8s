package com.pawbridge.backend.store.internal.cart.web;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class CartAddRequest {
    private Long skuId;
    private Integer quantity;
}
