package com.pawbridge.backend.user.api;

import java.util.Optional;

public interface UserNicknameQuery {
    /**
     * Returns empty only when the user row is absent. Stored blank nicknames are preserved.
     */
    Optional<String> findNickname(long userId);
}
