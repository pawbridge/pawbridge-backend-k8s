package com.pawbridge.backend.user.internal.session.application;

public interface AuthSessions {
    Tokens refresh(String refreshToken);
    void logout(long userId);
    record Tokens(String accessToken, String refreshToken) {}
}
