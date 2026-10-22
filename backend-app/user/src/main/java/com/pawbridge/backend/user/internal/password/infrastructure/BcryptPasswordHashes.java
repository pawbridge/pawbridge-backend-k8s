package com.pawbridge.backend.user.internal.password.infrastructure;

import com.pawbridge.backend.user.internal.password.application.PasswordHashes;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

public final class BcryptPasswordHashes implements PasswordHashes {
    // The legacy user-service registered a default-strength BCryptPasswordEncoder.
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    @Override
    public boolean matches(String rawPassword, String encodedPassword) {
        return encoder.matches(rawPassword, encodedPassword);
    }

    @Override
    public String encode(String rawPassword) {
        return encoder.encode(rawPassword);
    }
}
