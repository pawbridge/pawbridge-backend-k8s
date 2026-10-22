package com.pawbridge.backend.user.internal.password.application;

public interface PasswordHashes {
    boolean matches(String rawPassword, String encodedPassword);

    String encode(String rawPassword);
}
