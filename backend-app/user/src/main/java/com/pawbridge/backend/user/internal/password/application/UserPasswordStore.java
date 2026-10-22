package com.pawbridge.backend.user.internal.password.application;

import java.util.Optional;

public interface UserPasswordStore {
    Optional<StoredCredentials> findCredentials(long userId);

    void updatePassword(long userId, String encodedPassword);
}
