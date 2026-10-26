package com.pawbridge.backend.user.internal.oauth.application

import com.pawbridge.backend.user.internal.session.application.IssuedRefreshToken

interface SocialLogin {
    // MutableMap keeps the exact Java parameter type Map<String, Object> (a read-only Map<String, Any> would
    // become Map<String, ? extends Object> and Java implementers could no longer override it).
    fun load(registrationId: String?, attributes: MutableMap<String, Any?>): SocialUser

    /** Starts a new refresh session for the user; the caller puts the token only in the refresh cookie. */
    fun complete(user: SocialUser): IssuedRefreshToken

    @JvmRecord
    data class SocialUser(
        val userId: Long,
        val email: String?,
        val name: String?,
        val role: String?,
        val careRegNo: String?,
        val provider: String?,
    )
}
