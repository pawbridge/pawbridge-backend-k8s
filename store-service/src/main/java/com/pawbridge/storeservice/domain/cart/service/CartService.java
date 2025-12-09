package com.pawbridge.storeservice.domain.cart.service;

import com.pawbridge.storeservice.domain.cart.dto.CartAddRequest;
import com.pawbridge.storeservice.domain.cart.dto.CartItemResponse;

import java.util.List;

public interface CartService {
    void addToCart(Long userId, CartAddRequest request);
    List<CartItemResponse> getMyCart(Long userId);
    void updateQuantity(Long cartItemId, int quantity);
    void removeCartItem(Long cartItemId);
}
