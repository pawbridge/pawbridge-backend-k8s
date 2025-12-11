package com.pawbridge.storeservice.domain.mypage.service;

import com.pawbridge.storeservice.domain.cart.entity.Cart;
import com.pawbridge.storeservice.domain.mypage.dto.CartResponse;
import com.pawbridge.storeservice.domain.mypage.repository.CartRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 마이페이지용 장바구니 서비스 구현
 * - FeignClient 전용 단순 조회
 * - MySQL 사용
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MyPageCartServiceImpl implements MyPageCartService {

    private final CartRepository cartRepository;

    /**
     * 사용자별 장바구니 조회 (MySQL)
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<CartResponse> findByUserId(Long userId) {
        log.debug("[MyPage] 사용자별 장바구니 조회: userId={}", userId);

        Optional<Cart> cart = cartRepository.findByUserId(userId);

        return cart.map(CartResponse::from);
    }
}
