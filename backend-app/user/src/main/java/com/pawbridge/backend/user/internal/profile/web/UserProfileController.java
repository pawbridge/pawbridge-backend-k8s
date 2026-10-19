package com.pawbridge.backend.user.internal.profile.web;

import com.pawbridge.backend.user.internal.profile.application.UserProfile;
import com.pawbridge.backend.user.internal.profile.application.UserProfileQuery;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class UserProfileController {
    private final UserProfileQuery profiles;

    public UserProfileController(UserProfileQuery profiles) {
        this.profiles = profiles;
    }

    @GetMapping("/api/v1/users/me")
    public Response<UserProfile> me(@AuthenticationPrincipal Long userId) {
        return new Response<>(200, "요청이 성공적으로 처리되었습니다.", profiles.require(userId));
    }

    public record Response<T>(int code, String message, T data) {
    }
}
