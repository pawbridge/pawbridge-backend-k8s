package com.pawbridge.backend.user.internal.emailverification.web

import com.pawbridge.backend.user.internal.emailverification.application.EmailVerifications
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class EmailVerificationController(private val verifications: EmailVerifications) {

    // The bodies stay non-null: a nullable Kotlin @RequestBody would make the body optional for Spring MVC.
    @PostMapping(SEND_PATH)
    fun send(@Valid @RequestBody request: SendVerificationCodeRequest): Response<Void> {
        verifications.sendVerificationCode(request.email)
        return Response(200, "인증 코드가 발송되었습니다.", null)
    }

    @PostMapping(VERIFY_PATH)
    fun verify(@Valid @RequestBody request: VerifyCodeRequest): Response<EmailVerifiedResponse> {
        val verified = verifications.verifyCode(request.email, request.code)
        return Response(200, "이메일 인증에 성공했습니다.", EmailVerifiedResponse(verified))
    }

    @JvmRecord
    data class EmailVerifiedResponse(val verified: Boolean)

    @JvmRecord
    data class Response<T>(
        val code: Int,
        val message: String?,
        val data: T?,
    )

    companion object {
        const val SEND_PATH = "/api/v1/email/send"
        const val VERIFY_PATH = "/api/v1/email/verify"
    }
}
