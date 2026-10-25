package com.pawbridge.backend.user.internal.password.web

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern

@JvmRecord
data class UpdatePasswordRequest(
    @field:NotBlank(message = "현재 비밀번호는 필수입니다.")
    val currentPassword: String?,
    @field:NotBlank(message = "새 비밀번호는 필수입니다.")
    @field:Pattern(
        regexp = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[@\$!%*#?&])[A-Za-z\\d@\$!%*#?&]{8,20}\$",
        message = "비밀번호는 8~20자의 영문, 숫자, 특수문자를 포함해야 합니다.",
    )
    val newPassword: String?,
) {
    override fun toString(): String {
        // Request values are secrets; validation and binding diagnostics must not echo them.
        return "UpdatePasswordRequest[currentPassword=<redacted>, newPassword=<redacted>]"
    }
}
