package com.pawbridge.userservice.dto.response;

import java.util.List;

public record FavoriteListResponseDto(
        Long userId,
        int totalCount,
        List<FavoriteResponseDto> favorites
) {
    public static FavoriteListResponseDto of(Long userId, List<FavoriteResponseDto> favorites) {
        return new FavoriteListResponseDto(
                userId,
                favorites.size(),
                favorites
        );
    }
}
