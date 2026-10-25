package com.pawbridge.backend.user.internal.session.infrastructure

import java.time.Duration

/**
 * Session lifetime rules.
 *
 * @param idleLifetime how long a session survives without a refresh
 * @param absoluteLifetime how long a session survives at most, however often it is refreshed
 * @param maxActiveSessions active sessions kept per user; older ones are revoked on login
 * @param reuseGrace how long the replaced token is still answered as a concurrent retry
 */
@JvmRecord
data class AuthSessionPolicy(
    val idleLifetime: Duration,
    val absoluteLifetime: Duration,
    val maxActiveSessions: Int,
    val reuseGrace: Duration,
) {
    init {
        requirePositive(idleLifetime, "idle lifetime")
        requirePositive(absoluteLifetime, "absolute lifetime")
        requirePositive(reuseGrace, "reuse grace")
        if (maxActiveSessions < 1) {
            throw IllegalArgumentException("At least one active session must be allowed")
        }
    }

    companion object {
        const val DEFAULT_MAX_ACTIVE_SESSIONS = 5

        @JvmField
        val DEFAULT_REUSE_GRACE: Duration = Duration.ofSeconds(60)

        @JvmStatic
        fun of(idleLifetimeMillis: Long, absoluteLifetimeDays: Int): AuthSessionPolicy =
            AuthSessionPolicy(
                Duration.ofMillis(idleLifetimeMillis),
                Duration.ofDays(absoluteLifetimeDays.toLong()),
                DEFAULT_MAX_ACTIVE_SESSIONS,
                DEFAULT_REUSE_GRACE,
            )

        private fun requirePositive(value: Duration, name: String) {
            if (value.isNegative || value.isZero) {
                throw IllegalArgumentException("The session $name must be positive")
            }
        }
    }
}
