package com.pawbridge.backend.user.internal.shelter.infrastructure

import com.pawbridge.backend.animal.api.AnimalShelterMembers
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager

/** User owns the membership observation; the caller owns the shared writer transaction. */
class JdbcAnimalShelterMembers(private val jdbc: JdbcTemplate) : AnimalShelterMembers {

    override fun exists(careRegNo: String?): Boolean {
        val source = jdbc.dataSource
        if (!TransactionSynchronizationManager.isActualTransactionActive() ||
            TransactionSynchronizationManager.isCurrentTransactionReadOnly() ||
            source == null ||
            !TransactionSynchronizationManager.hasResource(source)
        ) {
            throw IllegalStateException("An active shared shelter writer transaction is required")
        }
        // No role or deletion-pending predicate: any still-present association blocks deletion.
        return jdbc.queryForObject(EXISTS, Boolean::class.javaObjectType, careRegNo)
            ?: throw NullPointerException("Shelter membership observation is unavailable")
    }

    private companion object {
        private const val EXISTS = "SELECT EXISTS(SELECT 1 FROM pawbridge_user.users WHERE care_reg_no=?)"
    }
}
