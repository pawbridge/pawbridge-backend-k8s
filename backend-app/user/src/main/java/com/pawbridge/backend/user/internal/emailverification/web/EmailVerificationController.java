package com.pawbridge.backend.user.internal.emailverification.web;

import com.pawbridge.backend.user.internal.emailverification.application.EmailVerifications;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class EmailVerificationController {
    public static final String SEND_PATH = "/api/v1/email/send";
    public static final String VERIFY_PATH = "/api/v1/email/verify";

    private final EmailVerifications verifications;

    public EmailVerificationController(EmailVerifications verifications) {
        this.verifications = verifications;
    }

    @PostMapping(SEND_PATH)
    public Response<Void> send(@Valid @RequestBody SendVerificationCodeRequest request) {
        verifications.sendVerificationCode(request.email());
        return new Response<>(200, "인증 코드가 발송되었습니다.", null);
    }

    @PostMapping(VERIFY_PATH)
    public Response<EmailVerifiedResponse> verify(@Valid @RequestBody VerifyCodeRequest request) {
        boolean verified = verifications.verifyCode(request.email(), request.code());
        return new Response<>(200, "이메일 인증에 성공했습니다.", new EmailVerifiedResponse(verified));
    }

    public record EmailVerifiedResponse(boolean verified) {
    }

    public record Response<T>(int code, String message, T data) {
    }
}
