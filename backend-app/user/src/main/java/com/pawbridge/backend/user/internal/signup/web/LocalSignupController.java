package com.pawbridge.backend.user.internal.signup.web;

import com.pawbridge.backend.user.internal.emailverification.web.EmailVerificationController.Response;
import com.pawbridge.backend.user.internal.signup.application.LocalSignup;
import com.pawbridge.backend.user.internal.signup.application.SignedUp;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class LocalSignupController {
    public static final String PATH = "/api/v1/users/signup";

    private final LocalSignup signups;

    public LocalSignupController(LocalSignup signups) {
        this.signups = signups;
    }

    @PostMapping(PATH)
    public Response<SignUpResponse> signUp(@Valid @RequestBody SignupRequest request) {
        SignedUp saved = signups.signUp(request.email(), request.name(), request.password(), request.rePassword(),
                request.role(), request.careRegNo());
        return new Response<>(200, "요청이 성공적으로 처리되었습니다.",
                new SignUpResponse(saved.userId(), saved.email(), saved.name(), saved.nickname()));
    }

    public record SignUpResponse(Long userId, String email, String name, String nickname) {
    }
}
