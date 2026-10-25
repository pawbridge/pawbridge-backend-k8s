package com.pawbridge.backend.user.internal.nickname.web

import com.pawbridge.backend.user.internal.nickname.application.ChangeUserNickname
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class UserNicknameController(private val nicknames: ChangeUserNickname) {

    // The body stays non-null: a nullable Kotlin @RequestBody would make the body optional for Spring MVC.
    // The principal stays boxed like the Java signature; a missing principal fails with NullPointerException
    // exactly as the Java unboxing did.
    @PutMapping("/api/v1/users/me/nickname")
    fun updateNickname(
        @AuthenticationPrincipal userId: Long?,
        @Valid @RequestBody request: UpdateNicknameRequest,
    ): Response<Void> {
        nicknames.change(userId!!, request.nickname)
        return Response(200, "닉네임이 변경되었습니다.", null)
    }

    @JvmRecord
    data class Response<T>(
        val code: Int,
        val message: String?,
        val data: T?,
    )
}
