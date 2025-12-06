package com.pawbridge.storeservice.domain.cart.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class CartAddRequest {
    private Long skuId;
    private Integer quantity;
}
