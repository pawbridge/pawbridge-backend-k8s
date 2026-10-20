package com.pawbridge.backend.user.internal.oauth.application;

public final class GoogleLoginRejectedException extends RuntimeException {
    public GoogleLoginRejectedException(String message) { super(message); }
}
