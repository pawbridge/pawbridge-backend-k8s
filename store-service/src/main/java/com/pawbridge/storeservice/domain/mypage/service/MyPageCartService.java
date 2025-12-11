package com.pawbridge.storeservice.domain.mypage.service;

import com.pawbridge.storeservice.domain.mypage.dto.CartResponse;

import java.util.Optional;

/**
 * 마이페이지용 장바구니 서비스
 * - FeignClient 전용 단순 조회
 * - MySQL 사용
 */
public interface MyPageCartService {

    /**
     * 사용자별 장바구니 조회
     * @param userId 사용자 ID
     * @return 장바구니 (없으면 빈 Optional)
     */
    Optional<CartResponse> findByUserId(Long userId);
}
