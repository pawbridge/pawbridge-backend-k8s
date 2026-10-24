package com.pawbridge.backend.user.internal.session.infrastructure;

import com.pawbridge.backend.user.internal.session.application.IssuedRefreshToken;
import com.pawbridge.backend.user.internal.session.application.UserSessionRevoker;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.jdbc.core.JdbcOperations;

/**
 * SQL for pawbridge_user.auth_sessions and its token history. Every method runs inside the caller's transaction.
 *
 * <p>All times come from the database clock (CURRENT_TIMESTAMP), so expiry checks and cookie lifetimes do not
 * depend on the application host's clock or time zone.
 */
public final class JdbcAuthSessionStore implements UserSessionRevoker {
    static final String LOCK_USER = "SELECT user_id FROM pawbridge_user.users WHERE user_id = ? FOR UPDATE";

    static final String INSERT_SESSION = """
            INSERT INTO pawbridge_user.auth_sessions
                (user_id, token_hash, created_at, last_used_at, expires_at, absolute_expires_at)
            VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                    LEAST(CURRENT_TIMESTAMP + ? * INTERVAL '1 millisecond',
                          CURRENT_TIMESTAMP + ? * INTERVAL '1 second'),
                    CURRENT_TIMESTAMP + ? * INTERVAL '1 second')
            RETURNING FLOOR(EXTRACT(EPOCH FROM expires_at - created_at))::bigint AS max_age_seconds
            """;

    static final String REVOKE_OLDEST_ACTIVE = """
            UPDATE pawbridge_user.auth_sessions SET revoked_at = CURRENT_TIMESTAMP
            WHERE session_id IN (
                SELECT session_id FROM pawbridge_user.auth_sessions
                WHERE user_id = ?
                  AND revoked_at IS NULL
                  AND expires_at > CURRENT_TIMESTAMP
                  AND absolute_expires_at > CURRENT_TIMESTAMP
                ORDER BY last_used_at DESC, session_id DESC
                OFFSET ?)
            """;

    static final String ROTATE = """
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
            """;

    // CURRENT_TIMESTAMP is fixed per transaction, so this equals the rotated_at the rotation just set.
    static final String RECORD_RETIRED = """
            INSERT INTO pawbridge_user.auth_session_token_history (token_hash, session_id, rotated_at)
            VALUES (?, ?, CURRENT_TIMESTAMP)
            """;

    static final String FIND_RETIRED = """
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
            """;

    /*
     * Locks the session only while the presented token is still the one retired by the latest rotation and the
     * successor has not been used since (last_used_at = rotated_at). A concurrent request waits on the row lock
     * and then re-checks these conditions against the committed row, so only one request can rotate again.
     */
    static final String LOCK_UNUSED_SUCCESSOR = """
            SELECT token_hash
            FROM pawbridge_user.auth_sessions
            WHERE session_id = ?
              AND last_used_at = rotated_at
              AND rotated_at = (SELECT rotated_at FROM pawbridge_user.auth_session_token_history WHERE token_hash = ?)
              AND revoked_at IS NULL
              AND expires_at > CURRENT_TIMESTAMP
              AND absolute_expires_at > CURRENT_TIMESTAMP
            FOR UPDATE
            """;

    static final String REVOKE_SESSION = """
            UPDATE pawbridge_user.auth_sessions SET revoked_at = CURRENT_TIMESTAMP
            WHERE session_id = ? AND revoked_at IS NULL
            """;

    static final String REVOKE_BY_TOKEN = """
            UPDATE pawbridge_user.auth_sessions SET revoked_at = CURRENT_TIMESTAMP
            WHERE token_hash = ? AND revoked_at IS NULL
            """;

    static final String REVOKE_ALL_FOR_USER = """
            UPDATE pawbridge_user.auth_sessions SET revoked_at = CURRENT_TIMESTAMP
            WHERE user_id = ? AND revoked_at IS NULL
            """;

    // Token history rows are removed by ON DELETE CASCADE.
    static final String DELETE_FINISHED = """
            DELETE FROM pawbridge_user.auth_sessions
            WHERE session_id IN (
                SELECT session_id FROM pawbridge_user.auth_sessions
                WHERE expires_at <= CURRENT_TIMESTAMP
                   OR absolute_expires_at <= CURRENT_TIMESTAMP
                   OR revoked_at <= CURRENT_TIMESTAMP - INTERVAL '1 day'
                LIMIT ?)
            """;

    private final JdbcOperations jdbc;
    private final AuthSessionPolicy policy;
    private final RefreshTokens refreshTokens;

    public JdbcAuthSessionStore(JdbcOperations jdbc, AuthSessionPolicy policy, RefreshTokens refreshTokens) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.refreshTokens = refreshTokens;
    }

    /**
     * Starts a session for a login. The user row stays locked until the caller's transaction ends, so two
     * concurrent logins of one user cannot both keep more than the allowed number of sessions.
     */
    public IssuedRefreshToken create(long userId) {
        List<Long> locked = jdbc.query(LOCK_USER, (row, number) -> row.getLong("user_id"), userId);
        if (locked.size() != 1) {
            throw new IncorrectResultSizeDataAccessException(1, locked.size());
        }
        String token = refreshTokens.generate();
        long absoluteSeconds = policy.absoluteLifetime().toSeconds();
        List<Long> maxAge = jdbc.query(INSERT_SESSION, (row, number) -> row.getLong("max_age_seconds"),
                userId,
                RefreshTokens.hash(token),
                policy.idleLifetime().toMillis(),
                absoluteSeconds,
                absoluteSeconds);
        if (maxAge.size() != 1) {
            throw new IncorrectResultSizeDataAccessException(1, maxAge.size());
        }
        jdbc.update(REVOKE_OLDEST_ACTIVE, userId, policy.maxActiveSessions());
        return new IssuedRefreshToken(token, maxAge.getFirst());
    }

    /**
     * Replaces an active session's token and keeps the presented hash in the token history. Empty when no
     * active session currently holds the presented token.
     */
    public Optional<Rotation> rotate(String presentedHash, String nextHash) {
        List<Rotation> rotated = jdbc.query(ROTATE,
                (row, number) -> new Rotation(
                        row.getLong("session_id"),
                        row.getLong("user_id"),
                        row.getLong("max_age_seconds")),
                nextHash,
                policy.idleLifetime().toMillis(),
                presentedHash);
        if (rotated.isEmpty()) {
            return Optional.empty();
        }
        Rotation rotation = rotated.getFirst();
        jdbc.update(RECORD_RETIRED, presentedHash, rotation.sessionId());
        return Optional.of(rotation);
    }

    /** Finds the session that once held the presented token, if the token has been rotated away. */
    public Optional<RetiredToken> findRetired(String presentedHash) {
        List<RetiredToken> found = jdbc.query(FIND_RETIRED,
                (row, number) -> new RetiredToken(
                        row.getLong("session_id"),
                        row.getLong("user_id"),
                        row.getBoolean("within_grace"),
                        row.getBoolean("latest_retirement"),
                        row.getBoolean("active")),
                policy.reuseGrace().toSeconds(),
                presentedHash);
        return found.stream().findFirst();
    }

    /**
     * Recovers from a lost rotation response: the browser still sends the token retired by the latest rotation
     * and that rotation's successor was never used, so the successor is retired too and a new token is issued.
     * Empty when another request already moved the session on.
     */
    public Optional<Rotation> rotateUnusedSuccessor(long sessionId, String presentedHash, String nextHash) {
        List<String> successor = jdbc.query(LOCK_UNUSED_SUCCESSOR, (row, number) -> row.getString("token_hash"),
                sessionId, presentedHash);
        if (successor.isEmpty()) {
            return Optional.empty();
        }
        return rotate(successor.getFirst(), nextHash);
    }

    public void revoke(long sessionId) {
        jdbc.update(REVOKE_SESSION, sessionId);
    }

    public void revokeByTokenHash(String tokenHash) {
        jdbc.update(REVOKE_BY_TOKEN, tokenHash);
    }

    @Override
    public void revokeAll(long userId) {
        jdbc.update(REVOKE_ALL_FOR_USER, userId);
    }

    /** Deletes at most {@code limit} expired or long-revoked sessions and returns how many were deleted. */
    public int deleteFinished(int limit) {
        return jdbc.update(DELETE_FINISHED, limit);
    }

    public record Rotation(long sessionId, long userId, long maxAgeSeconds) { }

    /**
     * @param withinGrace the token was retired less than the reuse grace ago
     * @param latestRetirement the token was retired by the session's most recent rotation
     */
    public record RetiredToken(long sessionId, long userId, boolean withinGrace, boolean latestRetirement,
            boolean active) { }
}
