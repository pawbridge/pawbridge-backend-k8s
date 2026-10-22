package com.pawbridge.backend.user.internal.passwordreset.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record PasswordResetVerifyRequest(
        @NotBlank(message = "이메일은 필수입니다.")
        @Email(message = "올바른 이메일 형식이 아닙니다.")
        String email,
        @NotBlank(message = "인증코드는 필수입니다.")
        String code,
        @NotBlank(message = "새 비밀번호는 필수입니다.")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[@$!%*#?&])[A-Za-z\\d@$!%*#?&]{8,20}$",
                message = "비밀번호는 8~20자의 영문, 숫자, 특수문자를 포함해야 합니다.")
        String newPassword) {
    @Override
    public String toString() {
        return "PasswordResetVerifyRequest[email=" + email + ", code=<redacted>, newPassword=<redacted>]";
    }
}
