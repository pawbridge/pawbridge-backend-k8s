package com.pawbridge.backend.user.internal.oauth.application;

public final class SocialLoginRejectedException extends RuntimeException {
    public SocialLoginRejectedException(String message) { super(message); }
}
