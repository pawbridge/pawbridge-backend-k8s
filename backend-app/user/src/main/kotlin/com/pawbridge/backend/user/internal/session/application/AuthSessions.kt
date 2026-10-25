package com.pawbridge.backend.user.internal.session.application

interface AuthSessions {

    /**
     * Rotates the presented refresh token once.
     *
     * @throws AuthSessionRejectedException when the token is missing, unknown, inactive or reused;
     *         a reused token's session is revoked and committed before this is thrown
     */
    fun refresh(refreshToken: String?): RefreshResult

    /** Revokes the session of the presented token, if any. A missing or unknown token is not an error. */
    fun logout(refreshToken: String?)

    sealed interface RefreshResult {
        fun accessToken(): String?
    }

    // The two results are plain data classes, not @JvmRecord: a record's accessor `accessToken()` would have the
    // same JVM signature as the interface method above, which Java callers use on the RefreshResult type.
    // The record-style accessors are written out so the Java surface stays the same.

    /** The presented token was replaced; the browser must store the new token. */
    data class Rotated(
        val accessToken: String?,
        val refreshToken: IssuedRefreshToken?,
    ) : RefreshResult {
        override fun accessToken(): String? = accessToken

        fun refreshToken(): IssuedRefreshToken? = refreshToken

        override fun toString(): String {
            return "Rotated"
        }
    }

    /**
     * A concurrent request rotated the same token a moment ago. Only an access token is returned so
     * the cookie set by that other response stays in place.
     */
    data class AccessOnly(val accessToken: String?) : RefreshResult {
        override fun accessToken(): String? = accessToken

        override fun toString(): String {
            return "AccessOnly"
        }
    }
}
