package com.pawbridge.backend.user.internal.session.infrastructure;

import com.pawbridge.backend.user.internal.session.application.AuthSessionRejectedException;
import com.pawbridge.backend.user.internal.session.application.AuthSessionRejectedException.Reason;
import com.pawbridge.backend.user.internal.session.application.AuthSessions;
import com.pawbridge.backend.user.internal.session.application.IssuedRefreshToken;
import com.pawbridge.backend.user.internal.session.infrastructure.JdbcAuthSessionStore.RetiredToken;
import com.pawbridge.backend.user.internal.session.infrastructure.JdbcAuthSessionStore.Rotation;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.support.TransactionOperations;

/** Refresh-token rotation and logout. Token values and hashes are never logged. */
public final class JdbcAuthSessions implements AuthSessions {
    private static final Logger log = LoggerFactory.getLogger(JdbcAuthSessions.class);
    private static final String FIND_USER_CLAIMS =
            "SELECT user_id, email, name, role, care_reg_no FROM pawbridge_user.users WHERE user_id = ?";

    private final JdbcOperations jdbc;
    private final TransactionOperations transactions;
    private final JwtSessionTokens accessTokens;
    private final JdbcAuthSessionStore store;
    private final RefreshTokens refreshTokens;

    public JdbcAuthSessions(JdbcOperations jdbc, TransactionOperations transactions, JwtSessionTokens accessTokens,
            JdbcAuthSessionStore store, RefreshTokens refreshTokens) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.accessTokens = accessTokens;
        this.store = store;
        this.refreshTokens = refreshTokens;
    }

    @Override
    public RefreshResult refresh(String presentedToken) {
        if (presentedToken == null || presentedToken.isEmpty()) {
            throw new AuthSessionRejectedException(Reason.MISSING);
        }
        if (!RefreshTokens.wellFormed(presentedToken)) {
            throw new AuthSessionRejectedException(Reason.INVALID);
        }
        String presentedHash = RefreshTokens.hash(presentedToken);
        String nextToken = refreshTokens.generate();
        Outcome outcome = transactions.execute(status -> decide(presentedHash, nextToken));
        // Thrown only after commit: a reuse revocation must persist even though the request is rejected.
        if (outcome.rejection() != null) {
            throw new AuthSessionRejectedException(outcome.rejection());
        }
        return outcome.result();
    }

    /*
     * A token that is no longer current is looked up in the token history:
     * - retired more than the grace window ago, at any generation: reuse of a stolen token, revoke the session;
     * - retired within the window: a concurrent tab or a retry. If it was retired by the latest rotation and the
     *   successor was never used, the rotation response was probably lost, so rotate once more and set a cookie;
     *   otherwise another response already carries the newer cookie, so return an access token only.
     */
    private Outcome decide(String presentedHash, String nextToken) {
        String nextHash = RefreshTokens.hash(nextToken);
        Optional<Rotation> rotation = store.rotate(presentedHash, nextHash);
        if (rotation.isPresent()) {
            return rotated(rotation.get(), nextToken);
        }
        Optional<RetiredToken> retired = store.findRetired(presentedHash);
        if (retired.isEmpty()) {
            return Outcome.rejected(Reason.INVALID);
        }
        RetiredToken token = retired.get();
        if (!token.withinGrace()) {
            store.revoke(token.sessionId());
            log.warn("Refresh token reuse detected; revoked sessionId={} userId={}", token.sessionId(), token.userId());
            return Outcome.rejected(Reason.REUSED);
        }
        if (!token.active()) {
            return Outcome.rejected(Reason.INVALID);
        }
        if (token.latestRetirement()) {
            Optional<Rotation> recovered = store.rotateUnusedSuccessor(token.sessionId(), presentedHash, nextHash);
            if (recovered.isPresent()) {
                return rotated(recovered.get(), nextToken);
            }
        }
        return Outcome.success(new AccessOnly(accessTokenFor(token.userId())));
    }

    private Outcome rotated(Rotation rotation, String nextToken) {
        String accessToken = accessTokenFor(rotation.userId());
        var refreshToken = new IssuedRefreshToken(nextToken, rotation.maxAgeSeconds());
        return Outcome.success(new Rotated(accessToken, refreshToken));
    }

    private String accessTokenFor(long userId) {
        List<JwtSessionTokens.UserClaims> users = jdbc.query(FIND_USER_CLAIMS,
                (row, number) -> new JwtSessionTokens.UserClaims(
                        row.getLong("user_id"),
                        row.getString("email"),
                        row.getString("name"),
                        row.getString("role"),
                        row.getString("care_reg_no")),
                userId);
        if (users.isEmpty()) {
            // Sessions cascade with their user, so this means a concurrent deletion; undo the rotation.
            throw new AuthSessionRejectedException(Reason.INVALID);
        }
        return accessTokens.access(users.getFirst());
    }

    @Override
    public void logout(String presentedToken) {
        if (!RefreshTokens.wellFormed(presentedToken)) {
            return;
        }
        String presentedHash = RefreshTokens.hash(presentedToken);
        transactions.executeWithoutResult(status -> store.revokeByTokenHash(presentedHash));
    }

    private record Outcome(RefreshResult result, Reason rejection) {
        static Outcome success(RefreshResult result) {
            return new Outcome(result, null);
        }

        static Outcome rejected(Reason reason) {
            return new Outcome(null, reason);
        }
    }
}
