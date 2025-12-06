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

    @PostMapping
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

    @PatchMapping("/items/{itemId}")
    public ResponseEntity<Void> updateQuantity(
            @PathVariable Long itemId,
            @RequestParam int quantity) {
        cartService.updateQuantity(itemId, quantity);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/items/{itemId}")
    public ResponseEntity<Void> removeCartItem(
            @PathVariable Long itemId) {
        cartService.removeCartItem(itemId);
        return ResponseEntity.ok().build();
    }
}
