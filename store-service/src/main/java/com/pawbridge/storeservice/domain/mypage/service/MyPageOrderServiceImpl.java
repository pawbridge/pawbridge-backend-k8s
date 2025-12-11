package com.pawbridge.storeservice.domain.mypage.service;

import com.pawbridge.storeservice.domain.mypage.dto.OrderResponse;
import com.pawbridge.storeservice.domain.mypage.repository.OrderRepository;
import com.pawbridge.storeservice.domain.order.entity.Order;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 마이페이지용 주문 서비스 구현
 * - FeignClient 전용 단순 조회
 * - MySQL 사용
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MyPageOrderServiceImpl implements MyPageOrderService {

    private final OrderRepository orderRepository;

    /**
     * 사용자별 주문 목록 조회 (MySQL)
     */
    @Override
    @Transactional(readOnly = true)
    public Page<OrderResponse> findByUserId(Long userId, Pageable pageable) {
        log.debug("[MyPage] 사용자별 주문 목록 조회: userId={}", userId);

        Page<Order> orders = orderRepository.findByUserId(userId, pageable);

        return orders.map(OrderResponse::from);
    }
}
