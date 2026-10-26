package com.pawbridge.backend.user.internal.profile.web

import com.pawbridge.backend.user.internal.profile.application.UserProfile
import com.pawbridge.backend.user.internal.profile.application.UserProfileQuery
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class UserProfileController(private val profiles: UserProfileQuery) {

    // The principal stays boxed like the Java signature; a missing principal fails with NullPointerException
    // exactly as the Java unboxing did.
    @GetMapping("/api/v1/users/me")
    fun me(@AuthenticationPrincipal userId: Long?): Response<UserProfile> =
        Response(200, "요청이 성공적으로 처리되었습니다.", profiles.require(userId!!))

    @JvmRecord
    data class Response<T>(
        val code: Int,
        val message: String?,
        val data: T?,
    )
}
