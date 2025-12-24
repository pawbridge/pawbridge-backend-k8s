package com.pawbridge.storeservice.domain.wishlist.controller;

import com.pawbridge.storeservice.domain.wishlist.dto.WishlistAddRequest;
import com.pawbridge.storeservice.domain.wishlist.dto.WishlistAddResponse;
import com.pawbridge.storeservice.domain.wishlist.service.WishlistService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 상품 찜(Wishlist) API 컨트롤러
 */
@RestController
@RequestMapping("/api/wishlists")
@RequiredArgsConstructor
public class WishlistController {

    private final WishlistService wishlistService;

    /**
     * 찜 추가
     * POST /api/wishlists
     */
    @PostMapping
    public ResponseEntity<WishlistAddResponse> addWishlist(@RequestBody WishlistAddRequest request) {
        WishlistAddResponse response = wishlistService.addWishlist(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * 찜 삭제 (wishlistId로)
     * DELETE /api/wishlists/{wishlistId}
     */
    @DeleteMapping("/{wishlistId}")
    public ResponseEntity<Void> removeWishlist(@PathVariable Long wishlistId) {
        wishlistService.removeWishlist(wishlistId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 찜 삭제 (userId + productId로)
     * DELETE /api/wishlists?userId={userId}&productId={productId}
     */
    @DeleteMapping
    public ResponseEntity<Void> removeWishlistByUserAndProduct(
            @RequestParam Long userId,
            @RequestParam Long productId) {
        wishlistService.removeWishlistByUserAndProduct(userId, productId);
        return ResponseEntity.noContent().build();
    }
}
