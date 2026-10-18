package com.pawbridge.backend.user.api;

public final class UserNicknameUnavailableException extends RuntimeException {
    public UserNicknameUnavailableException(Throwable cause) {
        super("User nickname lookup is unavailable.", cause);
    }
}
