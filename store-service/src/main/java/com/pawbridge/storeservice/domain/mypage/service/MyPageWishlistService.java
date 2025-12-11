package com.pawbridge.storeservice.domain.mypage.service;

import com.pawbridge.storeservice.domain.mypage.dto.WishlistResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * 마이페이지용 위시리스트 서비스
 * - FeignClient 전용 단순 조회
 * - MySQL 사용
 */
public interface MyPageWishlistService {

    /**
     * 사용자별 찜 목록 조회
     * @param userId 사용자 ID
     * @param pageable 페이징 정보
     * @return 찜 목록
     */
    Page<WishlistResponse> findByUserId(Long userId, Pageable pageable);
}
