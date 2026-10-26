@file:Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")

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
    // The path variable is java.lang.Long deliberately: kotlin.Long is a primitive (a blank segment becomes a type
    // mismatch instead of the Java MissingPathVariableException). A non-null java.lang.Long keeps the Java
    // behaviour: required and boxed, so a blank segment fails before the handler runs.
    @GetMapping("/api/v1/favorites/{animalId}/check")
    fun isFavorite(
        @AuthenticationPrincipal userId: Long?,
        @PathVariable("animalId") animalId: java.lang.Long,
    ): Response<Boolean> =
        Response(200, "요청이 성공적으로 처리되었습니다.", favorites.isFavorite(userId!!, animalId.toLong()))

    @JvmRecord
    data class Response<T>(
        val code: Int,
        val message: String?,
        val data: T?,
    )
}
