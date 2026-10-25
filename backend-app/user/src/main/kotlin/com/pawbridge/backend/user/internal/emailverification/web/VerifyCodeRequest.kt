package com.pawbridge.backend.user.internal.emailverification.web

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

@JvmRecord
data class VerifyCodeRequest(
    @field:NotBlank(message = "이메일은 필수입니다.")
    @field:Email(message = "올바른 이메일 형식이 아닙니다.")
    @field:Size(max = 100, message = "이메일은 100자를 초과할 수 없습니다.")
    val email: String?,
    @field:NotBlank(message = "인증 코드는 필수입니다.")
    @field:Pattern(regexp = "^\\d{6}\$", message = "인증 코드는 6자리 숫자여야 합니다.")
    val code: String?,
) {
    override fun toString(): String = "VerifyCodeRequest[email=" + email + ", code=<redacted>]"
}
