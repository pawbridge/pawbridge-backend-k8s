package com.pawbridge.backend.user.internal.passwordreset.application;

import java.util.OptionalLong;

public interface PasswordResetAccounts {
    OptionalLong findLocalUserId(String email);

    void updatePassword(long userId, String encodedPassword);
}
