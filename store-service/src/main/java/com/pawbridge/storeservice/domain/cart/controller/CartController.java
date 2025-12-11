package com.pawbridge.storeservice.domain.cart.controller;

import com.pawbridge.storeservice.domain.cart.dto.CartAddRequest;
import com.pawbridge.storeservice.domain.cart.dto.CartItemResponse;
import com.pawbridge.storeservice.domain.cart.service.CartService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/carts")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    @PostMapping("/items")
    public ResponseEntity<Void> addToCart(
            @RequestHeader("X-User-Id") Long userId,
            @RequestBody CartAddRequest request) {
        cartService.addToCart(userId, request);
        return ResponseEntity.ok().build();
    }

    @GetMapping
    public ResponseEntity<List<CartItemResponse>> getMyCart(
            @RequestHeader("X-User-Id") Long userId) {
        List<CartItemResponse> responses = cartService.getMyCart(userId);
        return ResponseEntity.ok(responses);
    }

    @PatchMapping("/items/{skuId}")
    public ResponseEntity<Void> updateQuantity(
            @RequestHeader("X-User-Id") Long userId,
            @PathVariable Long skuId,
            @RequestParam int quantity) {
        cartService.updateQuantity(userId, skuId, quantity);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/items/{skuId}")
    public ResponseEntity<Void> removeCartItem(
            @RequestHeader("X-User-Id") Long userId,
            @PathVariable Long skuId) {
        cartService.removeCartItem(userId, skuId);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping
    public ResponseEntity<Void> clearCart(
            @RequestHeader("X-User-Id") Long userId) {
        cartService.clearCart(userId);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/reset")
    public ResponseEntity<Void> resetCartSystem() {
        cartService.resetSystem();
        return ResponseEntity.ok().build();
    }
}
