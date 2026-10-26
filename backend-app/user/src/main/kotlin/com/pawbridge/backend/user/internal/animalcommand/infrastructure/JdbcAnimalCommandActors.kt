package com.pawbridge.backend.user.internal.animalcommand.infrastructure

import com.pawbridge.backend.animal.api.AnimalCommandActors
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.Objects

/** Resolves the verified principal while the caller owns the shared writer transaction. */
class JdbcAnimalCommandActors(private val jdbc: JdbcTemplate) : AnimalCommandActors {

    override fun find(verifiedUserId: Long): AnimalCommandActors.Actor {
        if (verifiedUserId <= 0) {
            throw IllegalArgumentException("Verified User identity must be positive")
        }
        val source = jdbc.dataSource
        if (!TransactionSynchronizationManager.isActualTransactionActive() ||
            TransactionSynchronizationManager.isCurrentTransactionReadOnly() ||
            source == null ||
            !TransactionSynchronizationManager.hasResource(source)
        ) {
            throw IllegalStateException("An active shared writer transaction is required")
        }
        val found: List<AnimalCommandActors.Actor> = jdbc.query(
            MEMBER,
            RowMapper { row, _ ->
                AnimalCommandActors.Actor(
                    row.getLong("user_id"),
                    row.getString("role"),
                    row.getString("care_reg_no"),
                    true,
                    false,
                )
            },
            verifiedUserId,
        )
        if (found.isEmpty()) {
            return AnimalCommandActors.Actor(verifiedUserId, null, null, false, true)
        }
        val actor = found.first()
        val pending: Boolean = Objects.requireNonNull<Boolean>(
            jdbc.queryForObject(PENDING, Boolean::class.javaObjectType, verifiedUserId),
            "User deletion observation is unavailable",
        )
        // As in ContactMember, active is existence without pending deletion, not a users table column.
        // Animal owns the authorization decision for the mechanically mapped role and shelter identity.
        return AnimalCommandActors.Actor(actor.userId, actor.role, actor.careRegNo, !pending, pending)
    }

    private companion object {
        private const val MEMBER = "SELECT user_id,role,care_reg_no FROM pawbridge_user.users WHERE user_id=? FOR UPDATE"
        private const val PENDING = "SELECT EXISTS(SELECT 1 FROM pawbridge_user.contact_deletions WHERE user_id=?)"
    }
}
