package com.pawbridge.backend.user.internal.favorite.web

import com.pawbridge.backend.user.internal.favorite.application.FavoriteStatusQuery
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

@RestController
class FavoriteStatusController(private val favorites: FavoriteStatusQuery) {

    // The principal stays boxed like the Java signature; a missing principal fails with NullPointerException
    // exactly as the Java unboxing did.
    @GetMapping("/api/v1/favorites/{animalId}/check")
    fun isFavorite(
        @AuthenticationPrincipal userId: Long?,
        @PathVariable("animalId") animalId: Long,
    ): Response<Boolean> = Response(200, "요청이 성공적으로 처리되었습니다.", favorites.isFavorite(userId!!, animalId))

    // The Java record accessors stay for Java callers; the standard getters keep the JSON shape.
    data class Response<T>(
        val code: Int,
        val message: String?,
        val data: T?,
    ) {
        fun code(): Int = code

        fun message(): String? = message

        fun data(): T? = data
    }
}
