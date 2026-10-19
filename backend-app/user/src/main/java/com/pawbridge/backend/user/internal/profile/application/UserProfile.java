package com.pawbridge.backend.user.internal.profile.application;

import java.time.LocalDateTime;

public record UserProfile(
        long userId,
        String email,
        String name,
        String nickname,
        String provider,
        String role,
        LocalDateTime createdAt
) {
}
