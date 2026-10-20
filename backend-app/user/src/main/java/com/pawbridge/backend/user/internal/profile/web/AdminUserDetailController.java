package com.pawbridge.backend.user.internal.profile.web;

import com.pawbridge.backend.user.internal.profile.application.UserProfile;
import com.pawbridge.backend.user.internal.profile.application.UserProfileQuery;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class AdminUserDetailController {
    private final UserProfileQuery profiles;

    public AdminUserDetailController(UserProfileQuery profiles) {
        this.profiles = profiles;
    }

    @GetMapping("/api/v1/admin/users/{userId}")
    public UserProfileController.Response<UserProfile> get(@PathVariable("userId") Long userId) {
        return new UserProfileController.Response<>(200, "요청이 성공적으로 처리되었습니다.", profiles.require(userId));
    }
}
