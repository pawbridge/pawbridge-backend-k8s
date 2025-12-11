package com.pawbridge.storeservice.domain.mypage.service;

import com.pawbridge.storeservice.domain.product.entity.Wishlist;
import com.pawbridge.storeservice.domain.mypage.dto.WishlistResponse;
import com.pawbridge.storeservice.domain.mypage.repository.WishlistRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 마이페이지용 위시리스트 서비스 구현
 * - FeignClient 전용 단순 조회
 * - MySQL 사용
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MyPageWishlistServiceImpl implements MyPageWishlistService {

    private final WishlistRepository wishlistRepository;

    /**
     * 사용자별 찜 목록 조회 (MySQL)
     */
    @Override
    @Transactional(readOnly = true)
    public Page<WishlistResponse> findByUserId(Long userId, Pageable pageable) {
        log.debug("[MyPage] 사용자별 찜 목록 조회: userId={}", userId);

        Page<Wishlist> wishlists = wishlistRepository.findByUserId(userId, pageable);

        return wishlists.map(WishlistResponse::from);
    }
}
