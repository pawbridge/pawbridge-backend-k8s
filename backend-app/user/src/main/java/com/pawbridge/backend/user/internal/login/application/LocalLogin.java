package com.pawbridge.backend.user.internal.login.application;

import com.pawbridge.backend.user.internal.session.application.IssuedRefreshToken;
import java.util.Optional;

public interface LocalLogin {
    Optional<Account> findByEmail(String email);

    CompletedLogin complete(Account account);

    record Account(long userId, String email, String name, String password, String role, String careRegNo) {
        @Override public String toString() { return "LocalLogin.Account"; }
    }

    /** The access token goes in the response body; the refresh token only in the refresh cookie. */
    record CompletedLogin(String accessToken, IssuedRefreshToken refreshToken) {

        @Override
        public String toString() {
            return "LocalLogin.CompletedLogin";
        }
    }
}
