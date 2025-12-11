package com.pawbridge.userservice.dto.response;

import com.pawbridge.userservice.entity.Favorite;

import java.time.LocalDateTime;

public record FavoriteResponseDto(
        Long favoriteId,
        Long userId,
        Long animalId,
        LocalDateTime createdAt
) {
    public static FavoriteResponseDto fromEntity(Favorite favorite) {
        return new FavoriteResponseDto(
                favorite.getFavoriteId(),
                favorite.getUser().getUserId(),
                favorite.getAnimalId(),
                favorite.getCreatedAt()
        );
    }
}
