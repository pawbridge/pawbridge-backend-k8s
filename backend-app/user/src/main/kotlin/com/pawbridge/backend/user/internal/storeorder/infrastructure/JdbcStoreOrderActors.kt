package com.pawbridge.backend.user.internal.storeorder.infrastructure

import com.pawbridge.backend.store.api.StoreOrderActors
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.Objects

/** Reads current User state inside the caller's shared Store order transaction. */
class JdbcStoreOrderActors(private val jdbc: JdbcTemplate) : StoreOrderActors {

    override fun currentForWrite(verifiedUserId: Long): StoreOrderActors.Actor = current(verifiedUserId, true)

    override fun currentForRead(verifiedUserId: Long): StoreOrderActors.Actor = current(verifiedUserId, false)

    private fun current(verifiedUserId: Long, write: Boolean): StoreOrderActors.Actor {
        if (verifiedUserId <= 0) {
            throw IllegalArgumentException("Verified User identity must be positive")
        }
        val source = jdbc.dataSource
        if (!TransactionSynchronizationManager.isActualTransactionActive() ||
            source == null ||
            !TransactionSynchronizationManager.hasResource(source) ||
            (write && TransactionSynchronizationManager.isCurrentTransactionReadOnly())
        ) {
            throw IllegalStateException("An active shared Store order transaction is required")
        }
        if (!write) {
            // One statement observes existence and deletion together even under READ COMMITTED.
            val found: List<StoreOrderActors.Actor> = jdbc.query(
                READ_MEMBER,
                RowMapper { row, _ ->
                    val pending = row.getBoolean("pending")
                    StoreOrderActors.Actor(row.getLong("user_id"), !pending, pending)
                },
                verifiedUserId,
            )
            return if (found.isEmpty()) StoreOrderActors.Actor(verifiedUserId, false, true) else found.first()
        }
        val found: List<Long> = jdbc.query(
            LOCK_MEMBER,
            RowMapper { row, _ -> row.getLong("user_id") },
            verifiedUserId,
        )
        if (found.isEmpty()) {
            return StoreOrderActors.Actor(verifiedUserId, false, true)
        }
        val pending: Boolean = Objects.requireNonNull<Boolean>(
            jdbc.queryForObject(PENDING, Boolean::class.javaObjectType, verifiedUserId),
            "User deletion observation is unavailable",
        )
        // Like ContactMember, availability means existence without pending contact deletion.
        // The API's active/deleted flags are derived state, not physical users table columns.
        return StoreOrderActors.Actor(found.first(), !pending, pending)
    }

    private companion object {
        private const val MEMBER = "SELECT user_id FROM pawbridge_user.users WHERE user_id=?"
        private const val LOCK_MEMBER = "$MEMBER FOR UPDATE"
        private const val PENDING = "SELECT EXISTS(SELECT 1 FROM pawbridge_user.contact_deletions WHERE user_id=?)"

        // The trailing newline matches the former Java text block; unit tests compare the SQL text exactly.
        private val READ_MEMBER = """
            SELECT u.user_id, d.user_id IS NOT NULL AS pending
            FROM pawbridge_user.users u
            LEFT JOIN pawbridge_user.contact_deletions d ON d.user_id = u.user_id
            WHERE u.user_id = ?
        """.trimIndent() + "\n"
    }
}
