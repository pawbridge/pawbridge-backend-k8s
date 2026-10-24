package com.pawbridge.backend.user.internal.session.application;

public interface AuthSessions {

    /**
     * Rotates the presented refresh token once.
     *
     * @throws AuthSessionRejectedException when the token is missing, unknown, inactive or reused;
     *         a reused token's session is revoked and committed before this is thrown
     */
    RefreshResult refresh(String refreshToken);

    /** Revokes the session of the presented token, if any. A missing or unknown token is not an error. */
    void logout(String refreshToken);

    sealed interface RefreshResult permits Rotated, AccessOnly {
        String accessToken();
    }

    /** The presented token was replaced; the browser must store the new token. */
    record Rotated(String accessToken, IssuedRefreshToken refreshToken) implements RefreshResult {

        @Override
        public String toString() {
            return "Rotated";
        }
    }

    /**
     * A concurrent request rotated the same token a moment ago. Only an access token is returned so
     * the cookie set by that other response stays in place.
     */
    record AccessOnly(String accessToken) implements RefreshResult {

        @Override
        public String toString() {
            return "AccessOnly";
        }
    }
}
