package com.pawbridge.backend.user.internal.password.application;

import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException;
import org.springframework.transaction.annotation.Transactional;

public class ChangeUserPassword {
    private static final String LOCAL_PROVIDER = "LOCAL";

    private final UserPasswordStore users;
    private final PasswordHashes hashes;

    public ChangeUserPassword(UserPasswordStore users, PasswordHashes hashes) {
        this.users = users;
        this.hashes = hashes;
    }

    @Transactional(transactionManager = "writeTransactionManager", timeout = 10)
    public void change(long userId, String currentPassword, String newPassword) {
        StoredCredentials credentials = users.findCredentials(userId).orElseThrow(UserNotFoundException::new);
        if (!LOCAL_PROVIDER.equals(credentials.provider())) {
            throw new OAuthUserCannotChangePasswordException();
        }
        if (!hashes.matches(currentPassword, credentials.password())) {
            throw new InvalidCurrentPasswordException();
        }
        // The legacy service compares the raw inputs only after the current password matched.
        if (currentPassword.equals(newPassword)) {
            throw new SamePasswordException();
        }
        users.updatePassword(userId, hashes.encode(newPassword));
    }
}
