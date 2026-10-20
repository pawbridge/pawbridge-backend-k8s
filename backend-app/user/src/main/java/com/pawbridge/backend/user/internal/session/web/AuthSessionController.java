package com.pawbridge.backend.user.internal.session.web;

import com.pawbridge.backend.user.internal.session.application.AuthSessions;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class AuthSessionController {
    private final AuthSessions sessions;
    public AuthSessionController(AuthSessions sessions) { this.sessions=sessions; }
    @PostMapping("/api/v1/auth/refresh")
    public Response<AuthSessions.Tokens> refresh(@Valid @RequestBody RefreshSessionRequest request) {
        return new Response<>(200,"토큰이 재발급되었습니다.",sessions.refresh(request.refreshToken()));
    }
    @PostMapping("/api/v1/auth/logout")
    public Response<Void> logout(@AuthenticationPrincipal Long userId) {
        sessions.logout(userId);
        return new Response<>(200,"로그아웃되었습니다.",null);
    }
    public record Response<T>(int code,String message,T data) {}
}
