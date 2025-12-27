package com.pawbridge.storeservice.domain.wishlist.service;

import com.pawbridge.storeservice.domain.wishlist.dto.WishlistAddRequest;
import com.pawbridge.storeservice.domain.wishlist.dto.WishlistAddResponse;

public interface WishlistService {
    
    /**
     * 찜 추가 (SKU 기반)
     */
    WishlistAddResponse addWishlist(WishlistAddRequest request);
    
    /**
     * 찜 삭제 (wishlistId로)
     */
    void removeWishlist(Long wishlistId);
    
    /**
     * 찜 삭제 (userId + skuId로)
     */
    void removeWishlistByUserAndSku(Long userId, Long skuId);
}
