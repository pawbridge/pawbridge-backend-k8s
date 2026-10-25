package com.pawbridge.backend.user.internal.session.infrastructure

import com.pawbridge.backend.user.internal.session.application.AuthSessionRejectedException
import com.pawbridge.backend.user.internal.session.application.AuthSessionRejectedException.Reason
import com.pawbridge.backend.user.internal.session.application.AuthSessions
import com.pawbridge.backend.user.internal.session.application.IssuedRefreshToken
import com.pawbridge.backend.user.internal.session.infrastructure.JdbcAuthSessionStore.RetiredToken
import com.pawbridge.backend.user.internal.session.infrastructure.JdbcAuthSessionStore.Rotation
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.support.TransactionOperations
import java.util.Optional

/** Refresh-token rotation and logout. Token values and hashes are never logged. */
class JdbcAuthSessions(
    private val jdbc: JdbcOperations,
    private val transactions: TransactionOperations,
    private val accessTokens: JwtSessionTokens,
    private val store: JdbcAuthSessionStore,
    private val refreshTokens: RefreshTokens,
) : AuthSessions {

    override fun refresh(refreshToken: String?): AuthSessions.RefreshResult {
        if (refreshToken == null || refreshToken.isEmpty()) {
            throw AuthSessionRejectedException(Reason.MISSING)
        }
        if (!RefreshTokens.wellFormed(refreshToken)) {
            throw AuthSessionRejectedException(Reason.INVALID)
        }
        val presentedHash = RefreshTokens.hash(refreshToken)
        val nextToken = refreshTokens.generate()
        val outcome = transactions.execute { _ -> decide(presentedHash, nextToken) }!!
        // Thrown only after commit: a reuse revocation must persist even though the request is rejected.
        if (outcome.rejection != null) {
            throw AuthSessionRejectedException(outcome.rejection)
        }
        return outcome.result!!
    }

    /*
     * A token that is no longer current is looked up in the token history:
     * - retired more than the grace window ago, at any generation: reuse of a stolen token, revoke the session;
     * - retired within the window: a concurrent tab or a retry. If it was retired by the latest rotation and the
     *   successor was never used, the rotation response was probably lost, so rotate once more and set a cookie;
     *   otherwise another response already carries the newer cookie, so return an access token only.
     */
    private fun decide(presentedHash: String, nextToken: String): Outcome {
        val nextHash = RefreshTokens.hash(nextToken)
        val rotation = store.rotate(presentedHash, nextHash)
        if (rotation.isPresent) {
            return rotated(rotation.get(), nextToken)
        }
        val retired = store.findRetired(presentedHash)
        if (retired.isEmpty) {
            return Outcome.rejected(Reason.INVALID)
        }
        val token: RetiredToken = retired.get()
        if (!token.withinGrace) {
            store.revoke(token.sessionId)
            log.warn("Refresh token reuse detected; revoked sessionId={} userId={}", token.sessionId, token.userId)
            return Outcome.rejected(Reason.REUSED)
        }
        if (!token.active) {
            return Outcome.rejected(Reason.INVALID)
        }
        if (token.latestRetirement) {
            val recovered = store.rotateUnusedSuccessor(token.sessionId, presentedHash, nextHash)
            if (recovered.isPresent) {
                return rotated(recovered.get(), nextToken)
            }
        }
        return Outcome.success(AuthSessions.AccessOnly(accessTokenFor(token.userId)))
    }

    private fun rotated(rotation: Rotation, nextToken: String): Outcome {
        val accessToken = accessTokenFor(rotation.userId)
        val refreshToken = IssuedRefreshToken(nextToken, rotation.maxAgeSeconds)
        return Outcome.success(AuthSessions.Rotated(accessToken, refreshToken))
    }

    private fun accessTokenFor(userId: Long): String {
        val users: List<JwtSessionTokens.UserClaims> = jdbc.query(
            FIND_USER_CLAIMS,
            RowMapper { row, _ ->
                JwtSessionTokens.UserClaims(
                    row.getLong("user_id"),
                    row.getString("email"),
                    row.getString("name"),
                    row.getString("role"),
                    row.getString("care_reg_no"),
                )
            },
            userId,
        )
        if (users.isEmpty()) {
            // Sessions cascade with their user, so this means a concurrent deletion; undo the rotation.
            throw AuthSessionRejectedException(Reason.INVALID)
        }
        return accessTokens.access(users.first())
    }

    override fun logout(refreshToken: String?) {
        if (!RefreshTokens.wellFormed(refreshToken)) {
            return
        }
        val presentedHash = RefreshTokens.hash(refreshToken!!)
        transactions.executeWithoutResult { _ -> store.revokeByTokenHash(presentedHash) }
    }

    @JvmRecord
    private data class Outcome(
        val result: AuthSessions.RefreshResult?,
        val rejection: Reason?,
    ) {
        companion object {
            fun success(result: AuthSessions.RefreshResult): Outcome = Outcome(result, null)

            fun rejected(reason: Reason): Outcome = Outcome(null, reason)
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(JdbcAuthSessions::class.java)
        private const val FIND_USER_CLAIMS =
            "SELECT user_id, email, name, role, care_reg_no FROM pawbridge_user.users WHERE user_id = ?"
    }
}
