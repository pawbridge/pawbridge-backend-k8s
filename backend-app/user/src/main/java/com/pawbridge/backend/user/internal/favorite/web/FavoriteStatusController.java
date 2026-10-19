package com.pawbridge.backend.user.internal.favorite.web;

import com.pawbridge.backend.user.internal.favorite.application.FavoriteStatusQuery;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class FavoriteStatusController {
    private final FavoriteStatusQuery favorites;

    public FavoriteStatusController(FavoriteStatusQuery favorites) {
        this.favorites = favorites;
    }

    @GetMapping("/api/v1/favorites/{animalId}/check")
    public Response<Boolean> isFavorite(@AuthenticationPrincipal Long userId, @PathVariable("animalId") Long animalId) {
        return new Response<>(200, "요청이 성공적으로 처리되었습니다.", favorites.isFavorite(userId, animalId));
    }

    public record Response<T>(int code, String message, T data) {
    }
}
