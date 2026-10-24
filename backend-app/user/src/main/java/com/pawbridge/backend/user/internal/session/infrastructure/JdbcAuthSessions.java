package com.pawbridge.backend.user.internal.session.infrastructure;

import com.pawbridge.backend.user.internal.session.application.AuthSessionRejectedException;
import com.pawbridge.backend.user.internal.session.application.AuthSessionRejectedException.Reason;
import com.pawbridge.backend.user.internal.session.application.AuthSessions;
import com.pawbridge.backend.user.internal.session.application.IssuedRefreshToken;
import com.pawbridge.backend.user.internal.session.infrastructure.JdbcAuthSessionStore.ReplacedToken;
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

    private Outcome decide(String presentedHash, String nextToken) {
        Optional<Rotation> rotation = store.rotate(presentedHash, RefreshTokens.hash(nextToken));
        if (rotation.isPresent()) {
            String accessToken = accessTokenFor(rotation.get().userId());
            var refreshToken = new IssuedRefreshToken(nextToken, rotation.get().maxAgeSeconds());
            return Outcome.success(new Rotated(accessToken, refreshToken));
        }
        Optional<ReplacedToken> replaced = store.findByPreviousHash(presentedHash);
        if (replaced.isEmpty()) {
            return Outcome.rejected(Reason.INVALID);
        }
        ReplacedToken session = replaced.get();
        if (session.withinGrace() && session.active()) {
            // Two tabs refreshed with the same cookie; the other request already set the new cookie.
            return Outcome.success(new AccessOnly(accessTokenFor(session.userId())));
        }
        if (session.withinGrace()) {
            return Outcome.rejected(Reason.INVALID);
        }
        store.revoke(session.sessionId());
        log.warn("Refresh token reuse detected; revoked sessionId={} userId={}", session.sessionId(), session.userId());
        return Outcome.rejected(Reason.REUSED);
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
