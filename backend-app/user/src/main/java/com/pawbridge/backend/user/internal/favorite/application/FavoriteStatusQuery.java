package com.pawbridge.backend.user.internal.favorite.application;

public interface FavoriteStatusQuery {
    boolean isFavorite(long userId, long animalId);
}
