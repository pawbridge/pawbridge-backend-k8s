package com.pawbridge.userservice.dto.response;

import java.util.List;

public record FavoriteListResponseDto(
        Long userId,
        int totalCount,
        List<FavoriteWithAnimalDto> favorites  // 타입 변경!
) {
    public static FavoriteListResponseDto of(Long userId, List<FavoriteWithAnimalDto> favorites) {
        return new FavoriteListResponseDto(
                userId,
                favorites.size(),
                favorites
        );
    }
}