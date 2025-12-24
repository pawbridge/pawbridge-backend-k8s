package com.pawbridge.storeservice.domain.wishlist.service;

import com.pawbridge.storeservice.domain.wishlist.dto.WishlistAddRequest;
import com.pawbridge.storeservice.domain.wishlist.dto.WishlistAddResponse;

public interface WishlistService {
    
    /**
     * 찜 추가
     */
    WishlistAddResponse addWishlist(WishlistAddRequest request);
    
    /**
     * 찜 삭제 (wishlistId로)
     */
    void removeWishlist(Long wishlistId);
    
    /**
     * 찜 삭제 (userId + productId로)
     */
    void removeWishlistByUserAndProduct(Long userId, Long productId);
}
