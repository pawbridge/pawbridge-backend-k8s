package com.pawbridge.userservice.service;

import com.pawbridge.userservice.dto.response.AnimalResponse;
import com.pawbridge.userservice.dto.response.PageResponse;
import com.pawbridge.userservice.dto.response.WishlistResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * 마이페이지 서비스
 */
public interface MyPageService {

    /**
     * 내가 등록한 동물 조회 (보호소 직원용)
     * @param userId 사용자 ID
     * @param pageable 페이징 정보
     * @return 동물 목록
     */
    PageResponse<AnimalResponse> getRegisteredAnimals(Long userId, Pageable pageable);

    /**
     * 내 찜 목록 조회
     * @param userId 사용자 ID
     * @param pageable 페이징 정보
     * @return 찜 목록
     */
    Page<WishlistResponse> getWishlists(Long userId, Pageable pageable);
}
