package com.pawbridge.userservice.client;

import com.pawbridge.userservice.dto.response.OrderResponse;
import com.pawbridge.userservice.dto.response.WishlistResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Store Service Feign Client
 * - store-service와 통신
 * - 찜 목록, 주문 내역 조회에 사용
 */
@FeignClient(name = "store-service")
public interface StoreServiceClient {

    /**
     * 사용자별 찜 목록 조회
     * @param userId 사용자 ID
     * @param page 페이지 번호
     * @param size 페이지 크기
     * @param sort 정렬 조건
     * @return 찜 목록
     */
    @GetMapping("/api/v1/mypage/wishlists")
    Page<WishlistResponse> getWishlistsByUserId(
            @RequestParam("userId") Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort);

    /**
     * 사용자별 주문 목록 조회
     * @param userId 사용자 ID
     * @param page 페이지 번호
     * @param size 페이지 크기
     * @param sort 정렬 조건
     * @return 주문 목록
     */
    @GetMapping("/api/v1/mypage/orders")
    Page<OrderResponse> getOrdersByUserId(
            @RequestParam("userId") Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort);
}
