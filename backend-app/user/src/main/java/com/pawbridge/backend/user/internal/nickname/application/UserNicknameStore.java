package com.pawbridge.backend.user.internal.nickname.application;

import java.util.Optional;

public interface UserNicknameStore {
    Optional<String> findNickname(long userId);

    boolean existsByNickname(String nickname);

    void updateNickname(long userId, String nickname);
}
