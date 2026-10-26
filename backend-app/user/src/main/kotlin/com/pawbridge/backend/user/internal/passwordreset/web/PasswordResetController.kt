package com.pawbridge.backend.user.internal.passwordreset.web

import com.pawbridge.backend.user.internal.emailverification.web.EmailVerificationController.Response
import com.pawbridge.backend.user.internal.passwordreset.application.PasswordReset
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class PasswordResetController(private val resets: PasswordReset) {

    // The bodies stay non-null: a nullable Kotlin @RequestBody would make the body optional for Spring MVC.
    @PostMapping(REQUEST_PATH)
    fun request(@Valid @RequestBody request: PasswordResetRequest): Response<Void> {
        resets.request(request.email)
        return Response(200, "인증코드가 이메일로 발송되었습니다.", null)
    }

    @PostMapping(RESET_PATH)
    fun reset(@Valid @RequestBody request: PasswordResetVerifyRequest): Response<Void> {
        resets.reset(request.email, request.code, request.newPassword)
        return Response(200, "비밀번호가 재설정되었습니다.", null)
    }

    companion object {
        const val REQUEST_PATH = "/api/v1/auth/password/reset-request"
        const val RESET_PATH = "/api/v1/auth/password/reset"
    }
}
