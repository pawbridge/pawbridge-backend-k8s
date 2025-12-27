package com.pawbridge.storeservice.domain.wishlist.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class WishlistAddRequest {
    private Long userId;
    private Long skuId;
}
