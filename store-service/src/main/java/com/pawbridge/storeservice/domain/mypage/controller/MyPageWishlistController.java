package com.pawbridge.storeservice.domain.mypage.controller;

import com.pawbridge.storeservice.domain.mypage.dto.WishlistResponse;
import com.pawbridge.storeservice.domain.mypage.service.MyPageWishlistService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 마이페이지용 위시리스트 API 컨트롤러
 * - FeignClient 전용
 * - MySQL 기반 단순 조회
 */
@RestController
@RequestMapping("/api/v1/mypage/wishlists")
@RequiredArgsConstructor
public class MyPageWishlistController {

    private final MyPageWishlistService myPageWishlistService;

    /**
     * 사용자별 찜 목록 조회 (FeignClient용)
     * - GET /api/v1/mypage/wishlists?userId={userId}
     * - MySQL 사용
     * - user-service의 마이페이지 찜 목록 조회에 사용
     */
    @GetMapping
    public ResponseEntity<Page<WishlistResponse>> getWishlistsByUserId(
            @RequestParam Long userId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        Page<WishlistResponse> wishlists = myPageWishlistService.findByUserId(userId, pageable);
        return ResponseEntity.ok(wishlists);
    }
}
