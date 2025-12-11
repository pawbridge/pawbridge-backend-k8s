package com.pawbridge.storeservice.domain.mypage.controller;

import com.pawbridge.storeservice.domain.mypage.dto.OrderResponse;
import com.pawbridge.storeservice.domain.mypage.service.MyPageOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 마이페이지용 주문 API 컨트롤러
 * - FeignClient 전용
 * - MySQL 기반 단순 조회
 */
@RestController
@RequestMapping("/api/v1/mypage/orders")
@RequiredArgsConstructor
public class MyPageOrderController {

    private final MyPageOrderService myPageOrderService;

    /**
     * 사용자별 주문 목록 조회 (FeignClient용)
     * - GET /api/v1/mypage/orders?userId={userId}
     * - MySQL 사용
     * - user-service의 마이페이지 주문 내역 조회에 사용
     */
    @GetMapping
    public ResponseEntity<Page<OrderResponse>> getOrdersByUserId(
            @RequestParam Long userId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        Page<OrderResponse> orders = myPageOrderService.findByUserId(userId, pageable);
        return ResponseEntity.ok(orders);
    }
}
