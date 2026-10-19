package com.pawbridge.backend.user.internal.nickname.application;

import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException;
import org.springframework.transaction.annotation.Transactional;

public class ChangeUserNickname {
    private final UserNicknameStore users;

    public ChangeUserNickname(UserNicknameStore users) {
        this.users = users;
    }

    @Transactional(transactionManager = "writeTransactionManager", timeout = 10)
    public void change(long userId, String nickname) {
        String current = users.findNickname(userId).orElseThrow(UserNotFoundException::new);
        if (current.equals(nickname)) {
            return;
        }
        // The legacy database query includes this user's own row, including case-only changes.
        if (users.existsByNickname(nickname)) {
            throw new NicknameDuplicateException();
        }
        users.updateNickname(userId, nickname);
    }
}
