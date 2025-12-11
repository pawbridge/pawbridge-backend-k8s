package com.pawbridge.storeservice.domain.mypage.controller;

import com.pawbridge.storeservice.domain.mypage.dto.CartResponse;
import com.pawbridge.storeservice.domain.mypage.service.MyPageCartService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

/**
 * 마이페이지용 장바구니 API 컨트롤러
 * - FeignClient 전용
 * - MySQL 기반 단순 조회
 */
@RestController
@RequestMapping("/api/v1/mypage/cart")
@RequiredArgsConstructor
public class MyPageCartController {

    private final MyPageCartService myPageCartService;

    /**
     * 사용자별 장바구니 조회 (FeignClient용)
     * - GET /api/v1/mypage/cart?userId={userId}
     * - MySQL 사용
     * - user-service의 마이페이지 장바구니 조회에 사용
     */
    @GetMapping
    public ResponseEntity<CartResponse> getCartByUserId(@RequestParam Long userId) {
        Optional<CartResponse> cart = myPageCartService.findByUserId(userId);

        // 장바구니가 없으면 204 No Content 반환
        return cart.map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
