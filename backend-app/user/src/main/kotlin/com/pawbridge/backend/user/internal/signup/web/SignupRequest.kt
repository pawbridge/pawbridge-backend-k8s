package com.pawbridge.backend.user.internal.signup.web

import com.pawbridge.backend.user.internal.signup.application.SignupRole
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

@JvmRecord
data class SignupRequest(
    @field:Email(message = "유효한 이메일을 입력하세요.")
    @field:NotBlank(message = "이메일은 필수입니다.")
    val email: String?,
    @field:NotBlank(message = "이름은 필수입니다.")
    @field:Size(max = 20)
    val name: String?,
    @field:NotBlank(message = "비밀번호는 필수입니다.")
    @field:Size(min = 6, message = "비밀번호는 최소 6자 이상이어야 합니다.")
    val password: String?,
    @field:Size(min = 6, message = "비밀번호는 최소 6자 이상이어야 합니다.")
    val rePassword: String?,
    @field:NotNull(message = "회원 유형을 선택해주세요. (일반 회원 또는 보호소 회원)")
    val role: SignupRole?,
    @field:Size(max = 50, message = "보호소 등록번호는 최대 50자입니다.")
    val careRegNo: String?,
) {
    override fun toString(): String {
        // Request values include passwords; diagnostics must not echo them.
        return "SignupRequest[email=" + email + ", password=<redacted>, rePassword=<redacted>, role=" + role + "]"
    }
}
