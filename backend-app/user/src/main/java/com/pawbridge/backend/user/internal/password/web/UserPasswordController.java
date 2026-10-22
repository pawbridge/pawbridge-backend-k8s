package com.pawbridge.backend.user.internal.password.web;

import com.pawbridge.backend.user.internal.password.application.ChangeUserPassword;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class UserPasswordController {
    public static final String PATH = "/api/v1/users/me/password";

    private final ChangeUserPassword passwords;

    public UserPasswordController(ChangeUserPassword passwords) {
        this.passwords = passwords;
    }

    @PutMapping(PATH)
    public Response<Void> updatePassword(@AuthenticationPrincipal Long userId,
                                        @Valid @RequestBody UpdatePasswordRequest request) {
        passwords.change(userId, request.currentPassword(), request.newPassword());
        return new Response<>(200, "비밀번호가 변경되었습니다.", null);
    }

    public record Response<T>(int code, String message, T data) {
    }
}
