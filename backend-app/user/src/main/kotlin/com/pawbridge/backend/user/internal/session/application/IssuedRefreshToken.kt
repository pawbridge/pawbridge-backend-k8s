package com.pawbridge.backend.user.internal.session.application

/**
 * A newly issued opaque refresh token and how long the browser may keep it.
 *
 * @param value the raw token; it is only ever placed in the refresh cookie
 * @param maxAgeSeconds seconds until the earlier of the idle and absolute session expiry
 */
@JvmRecord
data class IssuedRefreshToken(
    val value: String?,
    val maxAgeSeconds: Long,
) {
    override fun toString(): String {
        return "IssuedRefreshToken[maxAgeSeconds=$maxAgeSeconds]"
    }
}
