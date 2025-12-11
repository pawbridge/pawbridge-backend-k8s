package com.pawbridge.userservice.controller;

import com.pawbridge.userservice.dto.response.AnimalResponse;
import com.pawbridge.userservice.dto.response.CartResponse;
import com.pawbridge.userservice.dto.response.FavoriteListResponseDto;
import com.pawbridge.userservice.dto.response.OrderResponse;
import com.pawbridge.userservice.dto.response.PageResponse;
import com.pawbridge.userservice.dto.response.WishlistResponse;
import com.pawbridge.userservice.service.FavoriteService;
import com.pawbridge.userservice.service.MyPageService;
import com.pawbridge.userservice.util.ResponseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 마이페이지 컨트롤러 (조회 전용)
 * - 사용자의 모든 마이페이지 데이터 조회
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/users/me")
public class MyPageController {

    private final MyPageService myPageService;
    private final FavoriteService favoriteService;

    /**
     * 내가 좋아요한 동물 목록 조회
     * - GET /api/v1/users/me/favorites
     */
    @GetMapping("/favorite-animals")
    public ResponseEntity<ResponseDTO<FavoriteListResponseDto>> getFavorites(
            @RequestHeader(value = "X-User-Id", required = true) Long userId) {

        FavoriteListResponseDto favoriteListResponseDto = favoriteService.getFavorites(userId);
        ResponseDTO<FavoriteListResponseDto> response = ResponseDTO.okWithData(favoriteListResponseDto);
        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }

    /**
     * 내가 등록한 동물 조회 (보호소 직원용)
     * - GET /api/v1/users/me/registered-animals
     * - Role: ROLE_SHELTER만 가능
     */
    @GetMapping("/registered-animals")
    public ResponseEntity<ResponseDTO<PageResponse<AnimalResponse>>> getRegisteredAnimals(
            @RequestHeader(value = "X-User-Id", required = true) Long userId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {

        PageResponse<AnimalResponse> animals = myPageService.getRegisteredAnimals(userId, pageable);
        ResponseDTO<PageResponse<AnimalResponse>> response = ResponseDTO.okWithData(animals);

        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }

    /**
     * 내 찜 목록 조회
     * - GET /api/v1/users/me/wishlists
     */
    @GetMapping("/wishlists")
    public ResponseEntity<ResponseDTO<Page<WishlistResponse>>> getWishlists(
            @RequestHeader(value = "X-User-Id", required = true) Long userId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {

        Page<WishlistResponse> wishlists = myPageService.getWishlists(userId, pageable);
        ResponseDTO<Page<WishlistResponse>> response = ResponseDTO.okWithData(wishlists);

        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }

    /**
     * 내 주문 내역 조회
     * - GET /api/v1/users/me/orders
     */
    @GetMapping("/orders")
    public ResponseEntity<ResponseDTO<Page<OrderResponse>>> getOrders(
            @RequestHeader(value = "X-User-Id", required = true) Long userId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {

        Page<OrderResponse> orders = myPageService.getOrders(userId, pageable);
        ResponseDTO<Page<OrderResponse>> response = ResponseDTO.okWithData(orders);

        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }

    /**
     * 내 장바구니 조회
     * - GET /api/v1/users/me/cart
     */
    @GetMapping("/cart")
    public ResponseEntity<ResponseDTO<CartResponse>> getCart(
            @RequestHeader(value = "X-User-Id", required = true) Long userId) {

        CartResponse cart = myPageService.getCart(userId);

        // 장바구니가 없으면 204 No Content
        if (cart == null) {
            return ResponseEntity.noContent().build();
        }

        ResponseDTO<CartResponse> response = ResponseDTO.okWithData(cart);
        return ResponseEntity
                .status(response.getCode())
                .body(response);
    }
}
