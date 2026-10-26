package com.pawbridge.backend.user.internal.passwordreset.web

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank

@JvmRecord
data class PasswordResetRequest(
    @field:NotBlank(message = "이메일은 필수입니다.")
    @field:Email(message = "올바른 이메일 형식이 아닙니다.")
    val email: String?,
)
