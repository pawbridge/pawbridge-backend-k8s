package com.pawbridge.storeservice.domain.wishlist.service;

import com.pawbridge.storeservice.domain.mypage.repository.MyWishlistRepository;
import com.pawbridge.storeservice.domain.product.entity.Product;
import com.pawbridge.storeservice.domain.product.entity.Wishlist;
import com.pawbridge.storeservice.domain.product.repository.ProductRepository;
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
    private final ProductRepository productRepository;

    @Override
    @Transactional
    public WishlistAddResponse addWishlist(WishlistAddRequest request) {
        // 중복 찜 확인
        if (wishlistRepository.existsByUserIdAndProductId(request.getUserId(), request.getProductId())) {
            throw new IllegalStateException("이미 찜한 상품입니다.");
        }

        // 상품 존재 확인
        Product product = productRepository.findById(request.getProductId())
                .orElseThrow(() -> new IllegalArgumentException("상품을 찾을 수 없습니다: " + request.getProductId()));

        // 찜 저장
        Wishlist wishlist = Wishlist.builder()
                .userId(request.getUserId())
                .product(product)
                .build();
        wishlistRepository.save(wishlist);

        log.info(">>> [WISHLIST] 찜 추가: userId={}, productId={}", request.getUserId(), request.getProductId());

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
    public void removeWishlistByUserAndProduct(Long userId, Long productId) {
        Wishlist wishlist = wishlistRepository.findByUserIdAndProductId(userId, productId)
                .orElseThrow(() -> new IllegalArgumentException("찜을 찾을 수 없습니다."));
        
        wishlistRepository.delete(wishlist);
        log.info(">>> [WISHLIST] 찜 삭제: userId={}, productId={}", userId, productId);
    }
}
