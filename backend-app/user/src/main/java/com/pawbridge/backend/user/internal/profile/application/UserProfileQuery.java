package com.pawbridge.backend.user.internal.profile.application;

public interface UserProfileQuery {
    UserProfile require(long userId);
}
