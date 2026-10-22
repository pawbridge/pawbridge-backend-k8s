package com.pawbridge.backend.user.internal.passwordreset.web;

import com.pawbridge.backend.user.internal.emailverification.web.EmailVerificationController.Response;
import com.pawbridge.backend.user.internal.passwordreset.application.PasswordReset;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class PasswordResetController {
    public static final String REQUEST_PATH = "/api/v1/auth/password/reset-request";
    public static final String RESET_PATH = "/api/v1/auth/password/reset";

    private final PasswordReset resets;

    public PasswordResetController(PasswordReset resets) {
        this.resets = resets;
    }

    @PostMapping(REQUEST_PATH)
    public Response<Void> request(@Valid @RequestBody PasswordResetRequest request) {
        resets.request(request.email());
        return new Response<>(200, "인증코드가 이메일로 발송되었습니다.", null);
    }

    @PostMapping(RESET_PATH)
    public Response<Void> reset(@Valid @RequestBody PasswordResetVerifyRequest request) {
        resets.reset(request.email(), request.code(), request.newPassword());
        return new Response<>(200, "비밀번호가 재설정되었습니다.", null);
    }
}
