package com.pawbridge.backend.user.internal.favorite.application

interface FavoriteStatusQuery {
    fun isFavorite(userId: Long, animalId: Long): Boolean
}
