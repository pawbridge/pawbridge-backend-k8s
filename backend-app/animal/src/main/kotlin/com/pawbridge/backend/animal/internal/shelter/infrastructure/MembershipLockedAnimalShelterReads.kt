package com.pawbridge.backend.animal.internal.shelter.infrastructure

import com.pawbridge.backend.animal.api.AnimalShelterReadUnavailableException
import com.pawbridge.backend.animal.api.AnimalShelterReads
import com.pawbridge.backend.animal.api.AnimalShelterReads.Shelter
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.support.TransactionSynchronizationManager
import javax.sql.DataSource

/** Keeps the approved shelter present until the caller commits the User association. */
class MembershipLockedAnimalShelterReads(
    private val delegate: AnimalShelterReads,
    private val jdbc: JdbcTemplate,
) : AnimalShelterReads {

    override fun findByCareRegNo(careRegNo: String?): Shelter? {
        val source = jdbc.dataSource
        requireWriter(source)
        val binding = TransactionSynchronizationManager.getResource(source!!)
        // Look up the shelter first through the delegate, which joins this transaction on the shared pool; lock the membership row below.
        val found = delegate.findByCareRegNo(careRegNo)
        requireWriter(source)
        if (jdbc.dataSource !== source || TransactionSynchronizationManager.getResource(source) !== binding) {
            throw unavailable()
        }
        if (found == null) {
            return null
        }
        val locked: List<Shelter>
        try {
            locked = jdbc.query(
                LOCK,
                RowMapper { row, _ ->
                    Shelter(row.getObject("id", Long::class.javaObjectType), row.getString("care_reg_no"), row.getString("name"))
                },
                careRegNo,
            )
        } catch (failure: RuntimeException) {
            throw AnimalShelterReadUnavailableException(failure)
        }
        if (locked.size != 1 || found.id != locked.first().id ||
            found.careRegNo != locked.first().careRegNo ||
            careRegNo != locked.first().careRegNo
        ) {
            throw unavailable()
        }
        return found
    }

    private companion object {
        private const val LOCK =
            "SELECT id,care_reg_no,name FROM pawbridge_animal.shelters WHERE care_reg_no=? FOR KEY SHARE"

        private fun requireWriter(source: DataSource?) {
            if (!TransactionSynchronizationManager.isActualTransactionActive() ||
                TransactionSynchronizationManager.isCurrentTransactionReadOnly() ||
                source == null || !TransactionSynchronizationManager.hasResource(source)
            ) {
                throw unavailable()
            }
        }

        private fun unavailable(): AnimalShelterReadUnavailableException {
            return AnimalShelterReadUnavailableException(
                IllegalStateException("Shelter membership locking is unavailable"),
            )
        }
    }
}
