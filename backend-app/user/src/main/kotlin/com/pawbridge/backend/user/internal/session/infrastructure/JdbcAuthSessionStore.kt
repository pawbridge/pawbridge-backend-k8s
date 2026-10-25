package com.pawbridge.backend.user.internal.session.infrastructure

import com.pawbridge.backend.user.internal.session.application.IssuedRefreshToken
import com.pawbridge.backend.user.internal.session.application.UserSessionRevoker
import org.springframework.dao.IncorrectResultSizeDataAccessException
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import java.util.Optional

/**
 * SQL for pawbridge_user.auth_sessions and its token history. Every method runs inside the caller's transaction.
 *
 * <p>All times come from the database clock (CURRENT_TIMESTAMP), so expiry checks and cookie lifetimes do not
 * depend on the application host's clock or time zone.
 */
class JdbcAuthSessionStore(
    private val jdbc: JdbcOperations,
    private val policy: AuthSessionPolicy,
    private val refreshTokens: RefreshTokens,
) : UserSessionRevoker {

    /**
     * Starts a session for a login. The user row stays locked until the caller's transaction ends, so two
     * concurrent logins of one user cannot both keep more than the allowed number of sessions.
     */
    fun create(userId: Long): IssuedRefreshToken {
        val locked: List<Long> = jdbc.query(LOCK_USER, RowMapper { row, _ -> row.getLong("user_id") }, userId)
        if (locked.size != 1) {
            throw IncorrectResultSizeDataAccessException(1, locked.size)
        }
        val token = refreshTokens.generate()
        val absoluteSeconds = policy.absoluteLifetime.toSeconds()
        val maxAge: List<Long> = jdbc.query(
            INSERT_SESSION,
            RowMapper { row, _ -> row.getLong("max_age_seconds") },
            userId,
            RefreshTokens.hash(token),
            policy.idleLifetime.toMillis(),
            absoluteSeconds,
            absoluteSeconds,
        )
        if (maxAge.size != 1) {
            throw IncorrectResultSizeDataAccessException(1, maxAge.size)
        }
        jdbc.update(REVOKE_OLDEST_ACTIVE, userId, policy.maxActiveSessions)
        return IssuedRefreshToken(token, maxAge.first())
    }

    /**
     * Replaces an active session's token and keeps the presented hash in the token history. Empty when no
     * active session currently holds the presented token.
     */
    fun rotate(presentedHash: String, nextHash: String): Optional<Rotation> {
        val rotated: List<Rotation> = jdbc.query(
            ROTATE,
            RowMapper { row, _ ->
                Rotation(
                    row.getLong("session_id"),
                    row.getLong("user_id"),
                    row.getLong("max_age_seconds"),
                )
            },
            nextHash,
            policy.idleLifetime.toMillis(),
            presentedHash,
        )
        if (rotated.isEmpty()) {
            return Optional.empty()
        }
        val rotation = rotated.first()
        jdbc.update(RECORD_RETIRED, presentedHash, rotation.sessionId)
        return Optional.of(rotation)
    }

    /** Finds the session that once held the presented token, if the token has been rotated away. */
    fun findRetired(presentedHash: String): Optional<RetiredToken> {
        val found: List<RetiredToken> = jdbc.query(
            FIND_RETIRED,
            RowMapper { row, _ ->
                RetiredToken(
                    row.getLong("session_id"),
                    row.getLong("user_id"),
                    row.getBoolean("within_grace"),
                    row.getBoolean("latest_retirement"),
                    row.getBoolean("active"),
                )
            },
            policy.reuseGrace.toSeconds(),
            presentedHash,
        )
        return found.stream().findFirst()
    }

    /**
     * Recovers from a lost rotation response: the browser still sends the token retired by the latest rotation
     * and that rotation's successor was never used, so the successor is retired too and a new token is issued.
     * Empty when another request already moved the session on.
     */
    fun rotateUnusedSuccessor(sessionId: Long, presentedHash: String, nextHash: String): Optional<Rotation> {
        val successor: List<String> = jdbc.query(
            LOCK_UNUSED_SUCCESSOR,
            RowMapper { row, _ -> row.getString("token_hash") },
            sessionId,
            presentedHash,
        )
        if (successor.isEmpty()) {
            return Optional.empty()
        }
        return rotate(successor.first(), nextHash)
    }

    fun revoke(sessionId: Long) {
        jdbc.update(REVOKE_SESSION, sessionId)
    }

    fun revokeByTokenHash(tokenHash: String) {
        jdbc.update(REVOKE_BY_TOKEN, tokenHash)
    }

    override fun revokeAll(userId: Long) {
        jdbc.update(REVOKE_ALL_FOR_USER, userId)
    }

    /** Deletes at most {@code limit} expired or long-revoked sessions and returns how many were deleted. */
    fun deleteFinished(limit: Int): Int = jdbc.update(DELETE_FINISHED, limit)

    @JvmRecord
    data class Rotation(
        val sessionId: Long,
        val userId: Long,
        val maxAgeSeconds: Long,
    )

    /**
     * @param withinGrace the token was retired less than the reuse grace ago
     * @param latestRetirement the token was retired by the session's most recent rotation
     */
    @JvmRecord
    data class RetiredToken(
        val sessionId: Long,
        val userId: Long,
        val withinGrace: Boolean,
        val latestRetirement: Boolean,
        val active: Boolean,
    )

    // The SQL constants are internal (public in bytecode) because the same-package Java tests compare them.
    // Each text block keeps the trailing newline of the former Java text block.
    companion object {
        internal const val LOCK_USER = "SELECT user_id FROM pawbridge_user.users WHERE user_id = ? FOR UPDATE"

        @JvmField
        internal val INSERT_SESSION = """
            INSERT INTO pawbridge_user.auth_sessions
                (user_id, token_hash, created_at, last_used_at, expires_at, absolute_expires_at)
            VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                    LEAST(CURRENT_TIMESTAMP + ? * INTERVAL '1 millisecond',
                          CURRENT_TIMESTAMP + ? * INTERVAL '1 second'),
                    CURRENT_TIMESTAMP + ? * INTERVAL '1 second')
            RETURNING FLOOR(EXTRACT(EPOCH FROM expires_at - created_at))::bigint AS max_age_seconds
        """.trimIndent() + "\n"

        @JvmField
        internal val REVOKE_OLDEST_ACTIVE = """
            UPDATE pawbridge_user.auth_sessions SET revoked_at = CURRENT_TIMESTAMP
            WHERE session_id IN (
                SELECT session_id FROM pawbridge_user.auth_sessions
                WHERE user_id = ?
                  AND revoked_at IS NULL
                  AND expires_at > CURRENT_TIMESTAMP
                  AND absolute_expires_at > CURRENT_TIMESTAMP
                ORDER BY last_used_at DESC, session_id DESC
                OFFSET ?)
        """.trimIndent() + "\n"

        @JvmField
        internal val ROTATE = """
            UPDATE pawbridge_user.auth_sessions
            SET token_hash = ?,
                rotated_at = CURRENT_TIMESTAMP,
                last_used_at = CURRENT_TIMESTAMP,
                expires_at = LEAST(CURRENT_TIMESTAMP + ? * INTERVAL '1 millisecond', absolute_expires_at)
            WHERE token_hash = ?
              AND revoked_at IS NULL
              AND expires_at > CURRENT_TIMESTAMP
              AND absolute_expires_at > CURRENT_TIMESTAMP
            RETURNING session_id, user_id,
                      FLOOR(EXTRACT(EPOCH FROM expires_at - CURRENT_TIMESTAMP))::bigint AS max_age_seconds
        """.trimIndent() + "\n"

        // CURRENT_TIMESTAMP is fixed per transaction, so this equals the rotated_at the rotation just set.
        @JvmField
        internal val RECORD_RETIRED = """
            INSERT INTO pawbridge_user.auth_session_token_history (token_hash, session_id, rotated_at)
            VALUES (?, ?, CURRENT_TIMESTAMP)
        """.trimIndent() + "\n"

        @JvmField
        internal val FIND_RETIRED = """
            SELECT history.session_id,
                   session.user_id,
                   history.rotated_at > CURRENT_TIMESTAMP - ? * INTERVAL '1 second' AS within_grace,
                   session.rotated_at = history.rotated_at AS latest_retirement,
                   (session.revoked_at IS NULL
                       AND session.expires_at > CURRENT_TIMESTAMP
                       AND session.absolute_expires_at > CURRENT_TIMESTAMP) AS active
            FROM pawbridge_user.auth_session_token_history history
            JOIN pawbridge_user.auth_sessions session ON session.session_id = history.session_id
            WHERE history.token_hash = ?
        """.trimIndent() + "\n"

        /*
         * Locks the session only while the presented token is still the one retired by the latest rotation and the
         * successor has not been used since (last_used_at = rotated_at). A concurrent request waits on the row lock
         * and then re-checks these conditions against the committed row, so only one request can rotate again.
         */
        @JvmField
        internal val LOCK_UNUSED_SUCCESSOR = """
            SELECT token_hash
            FROM pawbridge_user.auth_sessions
            WHERE session_id = ?
              AND last_used_at = rotated_at
              AND rotated_at = (SELECT rotated_at FROM pawbridge_user.auth_session_token_history WHERE token_hash = ?)
              AND revoked_at IS NULL
              AND expires_at > CURRENT_TIMESTAMP
              AND absolute_expires_at > CURRENT_TIMESTAMP
            FOR UPDATE
        """.trimIndent() + "\n"

        @JvmField
        internal val REVOKE_SESSION = """
            UPDATE pawbridge_user.auth_sessions SET revoked_at = CURRENT_TIMESTAMP
            WHERE session_id = ? AND revoked_at IS NULL
        """.trimIndent() + "\n"

        @JvmField
        internal val REVOKE_BY_TOKEN = """
            UPDATE pawbridge_user.auth_sessions SET revoked_at = CURRENT_TIMESTAMP
            WHERE token_hash = ? AND revoked_at IS NULL
        """.trimIndent() + "\n"

        @JvmField
        internal val REVOKE_ALL_FOR_USER = """
            UPDATE pawbridge_user.auth_sessions SET revoked_at = CURRENT_TIMESTAMP
            WHERE user_id = ? AND revoked_at IS NULL
        """.trimIndent() + "\n"

        // Token history rows are removed by ON DELETE CASCADE.
        @JvmField
        internal val DELETE_FINISHED = """
            DELETE FROM pawbridge_user.auth_sessions
            WHERE session_id IN (
                SELECT session_id FROM pawbridge_user.auth_sessions
                WHERE expires_at <= CURRENT_TIMESTAMP
                   OR absolute_expires_at <= CURRENT_TIMESTAMP
                   OR revoked_at <= CURRENT_TIMESTAMP - INTERVAL '1 day'
                LIMIT ?)
        """.trimIndent() + "\n"
    }
}
