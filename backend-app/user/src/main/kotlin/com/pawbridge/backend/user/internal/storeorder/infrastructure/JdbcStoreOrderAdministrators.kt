package com.pawbridge.backend.user.internal.storeorder.infrastructure

import com.pawbridge.backend.store.api.StoreOrderAdministrators
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.Objects

/** Reads current DB role; neither JWT role nor a request header grants administrator authority. */
class JdbcStoreOrderAdministrators(private val jdbc: JdbcTemplate) : StoreOrderAdministrators {

    override fun currentForRead(userId: Long): StoreOrderAdministrators.Actor = current(userId, false)

    override fun currentForWrite(userId: Long): StoreOrderAdministrators.Actor = current(userId, true)

    private fun current(userId: Long, write: Boolean): StoreOrderAdministrators.Actor {
        if (userId <= 0) {
            throw IllegalArgumentException("Verified User identity must be positive")
        }
        val source = jdbc.dataSource
        if (!TransactionSynchronizationManager.isActualTransactionActive() ||
            source == null ||
            !TransactionSynchronizationManager.hasResource(source) ||
            TransactionSynchronizationManager.isCurrentTransactionReadOnly() == write
        ) {
            throw IllegalStateException("A matching shared Store administrator transaction is required")
        }
        if (!write) {
            val found: List<StoreOrderAdministrators.Actor> = jdbc.query(
                READ_MEMBER,
                RowMapper { row, _ ->
                    val pending = row.getBoolean("pending")
                    StoreOrderAdministrators.Actor(row.getLong("user_id"), !pending, pending, row.getString("role"))
                },
                userId,
            )
            return if (found.isEmpty()) {
                StoreOrderAdministrators.Actor(userId, false, true, null)
            } else {
                found.first()
            }
        }
        val found: List<StoreOrderAdministrators.Actor> = jdbc.query(
            LOCK_MEMBER,
            RowMapper { row, _ ->
                StoreOrderAdministrators.Actor(row.getLong("user_id"), true, false, row.getString("role"))
            },
            userId,
        )
        if (found.isEmpty()) {
            return StoreOrderAdministrators.Actor(userId, false, true, null)
        }
        val pending: Boolean = Objects.requireNonNull<Boolean>(
            jdbc.queryForObject(PENDING, Boolean::class.javaObjectType, userId),
            "User deletion observation is unavailable",
        )
        val actor = found.first()
        return StoreOrderAdministrators.Actor(actor.userId(), !pending, pending, actor.role())
    }

    private companion object {
        private const val LOCK_MEMBER = "SELECT user_id,role FROM pawbridge_user.users WHERE user_id=? FOR UPDATE"
        private const val PENDING = "SELECT EXISTS(SELECT 1 FROM pawbridge_user.contact_deletions WHERE user_id=?)"

        // The trailing newline matches the former Java text block; unit tests compare the SQL text exactly.
        private val READ_MEMBER = """
            SELECT u.user_id, u.role, d.user_id IS NOT NULL AS pending
            FROM pawbridge_user.users u
            LEFT JOIN pawbridge_user.contact_deletions d ON d.user_id = u.user_id
            WHERE u.user_id = ?
        """.trimIndent() + "\n"
    }
}
