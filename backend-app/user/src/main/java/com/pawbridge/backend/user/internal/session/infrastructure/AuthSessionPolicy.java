package com.pawbridge.backend.user.internal.session.infrastructure;

import java.time.Duration;

/**
 * Session lifetime rules.
 *
 * @param idleLifetime how long a session survives without a refresh
 * @param absoluteLifetime how long a session survives at most, however often it is refreshed
 * @param maxActiveSessions active sessions kept per user; older ones are revoked on login
 * @param reuseGrace how long the replaced token is still answered as a concurrent retry
 */
public record AuthSessionPolicy(Duration idleLifetime, Duration absoluteLifetime, int maxActiveSessions,
        Duration reuseGrace) {
    public static final int DEFAULT_MAX_ACTIVE_SESSIONS = 5;
    public static final Duration DEFAULT_REUSE_GRACE = Duration.ofSeconds(60);

    public AuthSessionPolicy {
        requirePositive(idleLifetime, "idle lifetime");
        requirePositive(absoluteLifetime, "absolute lifetime");
        requirePositive(reuseGrace, "reuse grace");
        if (maxActiveSessions < 1) {
            throw new IllegalArgumentException("At least one active session must be allowed");
        }
    }

    public static AuthSessionPolicy of(long idleLifetimeMillis, int absoluteLifetimeDays) {
        return new AuthSessionPolicy(Duration.ofMillis(idleLifetimeMillis), Duration.ofDays(absoluteLifetimeDays),
                DEFAULT_MAX_ACTIVE_SESSIONS, DEFAULT_REUSE_GRACE);
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("The session " + name + " must be positive");
        }
    }
}
