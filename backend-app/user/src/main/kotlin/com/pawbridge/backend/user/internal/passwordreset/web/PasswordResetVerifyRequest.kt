package com.pawbridge.backend.user.internal.passwordreset.web

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern

@JvmRecord
data class PasswordResetVerifyRequest(
    @field:NotBlank(message = "이메일은 필수입니다.")
    @field:Email(message = "올바른 이메일 형식이 아닙니다.")
    val email: String?,
    @field:NotBlank(message = "인증코드는 필수입니다.")
    val code: String?,
    @field:NotBlank(message = "새 비밀번호는 필수입니다.")
    @field:Pattern(
        regexp = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[@\$!%*#?&])[A-Za-z\\d@\$!%*#?&]{8,20}\$",
        message = "비밀번호는 8~20자의 영문, 숫자, 특수문자를 포함해야 합니다.",
    )
    val newPassword: String?,
) {
    override fun toString(): String {
        return "PasswordResetVerifyRequest[email=$email, code=<redacted>, newPassword=<redacted>]"
    }
}
