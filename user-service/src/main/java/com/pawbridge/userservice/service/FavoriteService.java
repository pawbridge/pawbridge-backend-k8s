package com.pawbridge.userservice.service;

import com.pawbridge.userservice.dto.response.FavoriteListResponseDto;
import com.pawbridge.userservice.dto.response.FavoriteResponseDto;

public interface FavoriteService {

    /**
     * 찜 추가
     */
    FavoriteResponseDto addFavorite(Long userId, Long animalId);

    /**
     * 찜 제거
     */
    void removeFavorite(Long userId, Long animalId);

    /**
     * 찜 목록 조회
     */
    FavoriteListResponseDto getFavorites(Long userId);

    /**
     * 찜 여부 확인
     */
    boolean isFavorite(Long userId, Long animalId);
}
