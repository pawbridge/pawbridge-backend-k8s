package com.pawbridge.storeservice.domain.wishlist.service;

import com.pawbridge.storeservice.domain.mypage.repository.MyWishlistRepository;
import com.pawbridge.storeservice.domain.product.entity.ProductSKU;
import com.pawbridge.storeservice.domain.product.entity.Wishlist;
import com.pawbridge.storeservice.domain.product.repository.ProductSKURepository;
import com.pawbridge.storeservice.domain.wishlist.dto.WishlistAddRequest;
import com.pawbridge.storeservice.domain.wishlist.dto.WishlistAddResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class WishlistServiceImpl implements WishlistService {

    private final MyWishlistRepository wishlistRepository;
    private final ProductSKURepository skuRepository;

    @Override
    @Transactional
    public WishlistAddResponse addWishlist(WishlistAddRequest request) {
        // 중복 찜 확인 (동일 사용자 + 동일 SKU)
        if (wishlistRepository.existsByUserIdAndSkuId(request.getUserId(), request.getSkuId())) {
            throw new IllegalStateException("이미 찜한 상품입니다.");
        }

        // SKU 존재 확인
        ProductSKU sku = skuRepository.findById(request.getSkuId())
                .orElseThrow(() -> new IllegalArgumentException("SKU를 찾을 수 없습니다: " + request.getSkuId()));

        // 찜 저장
        Wishlist wishlist = Wishlist.builder()
                .userId(request.getUserId())
                .sku(sku)
                .build();
        wishlistRepository.save(wishlist);

        log.info(">>> [WISHLIST] 찜 추가: userId={}, skuId={}", request.getUserId(), request.getSkuId());

        return WishlistAddResponse.from(wishlist);
    }

    @Override
    @Transactional
    public void removeWishlist(Long wishlistId) {
        Wishlist wishlist = wishlistRepository.findById(wishlistId)
                .orElseThrow(() -> new IllegalArgumentException("찜을 찾을 수 없습니다: " + wishlistId));
        
        wishlistRepository.delete(wishlist);
        log.info(">>> [WISHLIST] 찜 삭제: wishlistId={}", wishlistId);
    }

    @Override
    @Transactional
    public void removeWishlistByUserAndSku(Long userId, Long skuId) {
        Wishlist wishlist = wishlistRepository.findByUserIdAndSkuId(userId, skuId)
                .orElseThrow(() -> new IllegalArgumentException("찜을 찾을 수 없습니다."));
        
        wishlistRepository.delete(wishlist);
        log.info(">>> [WISHLIST] 찜 삭제: userId={}, skuId={}", userId, skuId);
    }
}
