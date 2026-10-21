package com.pawbridge.backend.user.internal.login.application;

import com.pawbridge.backend.user.internal.session.application.AuthSessions;
import java.util.Optional;

public interface LocalLogin {
    Optional<Account> findByEmail(String email);
    AuthSessions.Tokens complete(Account account);

    record Account(long userId, String email, String name, String password, String role, String careRegNo) {
        @Override public String toString() { return "LocalLogin.Account"; }
    }
}
