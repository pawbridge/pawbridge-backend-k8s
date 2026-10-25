package com.pawbridge.backend.user.internal.password.web

import com.pawbridge.backend.user.internal.password.application.ChangeUserPassword
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class UserPasswordController(private val passwords: ChangeUserPassword) {

    // The body stays non-null: a nullable Kotlin @RequestBody would make the body optional for Spring MVC.
    // The principal stays boxed like the Java signature; a missing principal fails with NullPointerException
    // exactly as the Java unboxing did.
    @PutMapping(PATH)
    fun updatePassword(
        @AuthenticationPrincipal userId: Long?,
        @Valid @RequestBody request: UpdatePasswordRequest,
    ): Response<Void> {
        passwords.change(userId!!, request.currentPassword, request.newPassword)
        return Response(200, "비밀번호가 변경되었습니다.", null)
    }

    @JvmRecord
    data class Response<T>(
        val code: Int,
        val message: String?,
        val data: T?,
    )

    companion object {
        const val PATH = "/api/v1/users/me/password"
    }
}
