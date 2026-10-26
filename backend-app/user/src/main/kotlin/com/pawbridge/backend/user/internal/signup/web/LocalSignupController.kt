package com.pawbridge.backend.user.internal.signup.web

import com.pawbridge.backend.user.internal.emailverification.web.EmailVerificationController.Response
import com.pawbridge.backend.user.internal.signup.application.LocalSignup
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class LocalSignupController(private val signups: LocalSignup) {

    // The body stays non-null: a nullable Kotlin @RequestBody would make the body optional for Spring MVC.
    @PostMapping(PATH)
    fun signUp(@Valid @RequestBody request: SignupRequest): Response<SignUpResponse> {
        val saved = signups.signUp(
            request.email,
            request.name,
            request.password,
            request.rePassword,
            request.role,
            request.careRegNo,
        )
        return Response(
            200,
            "요청이 성공적으로 처리되었습니다.",
            SignUpResponse(saved.userId, saved.email, saved.name, saved.nickname),
        )
    }

    @JvmRecord
    data class SignUpResponse(
        val userId: Long?,
        val email: String?,
        val name: String?,
        val nickname: String?,
    )

    companion object {
        const val PATH = "/api/v1/users/signup"
    }
}
