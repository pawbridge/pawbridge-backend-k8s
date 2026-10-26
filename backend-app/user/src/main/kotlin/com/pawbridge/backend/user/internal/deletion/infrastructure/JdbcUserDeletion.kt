package com.pawbridge.backend.user.internal.deletion.infrastructure

import com.pawbridge.backend.user.internal.deletion.application.MemberCleanupUnavailableException
import com.pawbridge.backend.user.internal.deletion.application.UserDeletion
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException
import com.pawbridge.backend.user.internal.session.application.UserSessionRevoker
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.support.TransactionOperations
import java.util.function.LongConsumer

/** PostgreSQL coordination; the caller must not add a transaction around the whole operation. */
class JdbcUserDeletion(
    private val jdbc: JdbcOperations,
    private val transactions: TransactionOperations,
    private val communityCleanup: LongConsumer,
    private val sessions: UserSessionRevoker,
) : UserDeletion {

    constructor(jdbc: JdbcOperations, transactions: TransactionOperations, communityCleanup: LongConsumer) :
        this(jdbc, transactions, communityCleanup, UserSessionRevoker.NONE)

    override fun delete(userId: Long) {
        transactions.executeWithoutResult {
            val users = jdbc.query(
                "SELECT user_id FROM pawbridge_user.users WHERE user_id=? FOR UPDATE",
                RowMapper { row, _ -> row.getLong(1) },
                userId,
            )
            if (users.isEmpty()) {
                throw UserNotFoundException()
            }
            jdbc.update("INSERT INTO pawbridge_user.contact_deletions(user_id) VALUES (?) ON CONFLICT DO NOTHING", userId)
            // A member pending deletion must not refresh while the community cleanup runs or awaits a retry.
            sessions.revokeAll(userId)
        }

        try {
            communityCleanup.accept(userId)
        } catch (failure: RuntimeException) {
            // The committed pending marker is retained for an authorized retry, as in the source.
            throw MemberCleanupUnavailableException()
        }

        transactions.executeWithoutResult {
            jdbc.update("DELETE FROM pawbridge_user.favorites WHERE user_id=?", userId)
            jdbc.update("DELETE FROM pawbridge_user.shelter_applications WHERE user_id=?", userId)
            // Preserve deleteById: missing at lookup is a no-op, disappearance after lookup is stale.
            val users = jdbc.query(
                "SELECT user_id FROM pawbridge_user.users WHERE user_id=?",
                RowMapper { row, _ -> row.getLong(1) },
                userId,
            )
            if (!users.isEmpty() && jdbc.update("DELETE FROM pawbridge_user.users WHERE user_id=?", userId) != 1) {
                throw OptimisticLockingFailureException("Account disappeared during deletion")
            }
        }
    }
}
