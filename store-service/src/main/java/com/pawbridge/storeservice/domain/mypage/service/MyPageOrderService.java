package com.pawbridge.storeservice.domain.mypage.service;

import com.pawbridge.storeservice.domain.mypage.dto.OrderResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * 마이페이지용 주문 서비스
 * - FeignClient 전용 단순 조회
 * - MySQL 사용
 */
public interface MyPageOrderService {

    /**
     * 사용자별 주문 목록 조회
     * @param userId 사용자 ID
     * @param pageable 페이징 정보
     * @return 주문 목록
     */
    Page<OrderResponse> findByUserId(Long userId, Pageable pageable);
}
