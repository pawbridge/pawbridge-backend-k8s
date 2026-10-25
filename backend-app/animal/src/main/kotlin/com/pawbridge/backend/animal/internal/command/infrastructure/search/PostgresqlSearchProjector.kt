package com.pawbridge.backend.animal.internal.command.infrastructure.search

import com.pawbridge.backend.animal.internal.discovery.infrastructure.KoreanSearchAnalyzer
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * Bounded repair transactions; never called inside an API/Batch business transaction.
 * A zero-row page may mean rows are locked elsewhere, not that the entire corpus is ready.
 */
class PostgresqlSearchProjector(
    private val jdbc: JdbcTemplate,
    private val transaction: TransactionTemplate,
    private val analyzer: KoreanSearchAnalyzer,
) {
    fun refreshAnimals(limit: Int): Int = refreshAnimals(limit, true)

    fun refreshDirtyAnimals(limit: Int): Int = refreshAnimals(limit, false)

    private fun refreshAnimals(limit: Int, audit: Boolean): Int {
        validate(limit)
        val pending = if (audit) {
            "search_dirty OR NOT EXISTS (SELECT 1 FROM pawbridge_animal.animal_search_documents d " +
                "WHERE d.animal_id=a.id AND d.source_revision=a.search_revision AND d.analyzer_version=?)"
        } else {
            "search_dirty"
        }
        val parameters: Array<Any?> = if (audit) {
            arrayOf(KoreanSearchAnalyzer.VERSION, limit)
        } else {
            arrayOf(limit)
        }
        return transaction.execute { _ ->
            limits()
            // Lock source first: UPDATE/DELETE and other projectors use the same lock order.
            val rows = jdbc.queryForList(
                "SELECT id,search_revision,breed,color,special_mark,description,happen_place " +
                    "FROM pawbridge_animal.animals a WHERE " + pending +
                    " ORDER BY id LIMIT ? FOR UPDATE OF a SKIP LOCKED",
                *parameters,
            )
            for (row in rows) {
                SearchDocumentWriter.writeAnimal(jdbc, analyzer, row)
            }
            rows.size
        }!!
    }

    fun refreshShelters(limit: Int): Int = refreshShelters(limit, true)

    fun refreshDirtyShelters(limit: Int): Int = refreshShelters(limit, false)

    private fun refreshShelters(limit: Int, audit: Boolean): Int {
        validate(limit)
        val pending = if (audit) {
            "search_dirty OR NOT EXISTS (SELECT 1 FROM pawbridge_animal.shelter_search_documents d " +
                "WHERE d.shelter_id=s.id AND d.source_revision=s.search_revision AND d.analyzer_version=?)"
        } else {
            "search_dirty"
        }
        val parameters: Array<Any?> = if (audit) {
            arrayOf(KoreanSearchAnalyzer.VERSION, limit)
        } else {
            arrayOf(limit)
        }
        // Separate transaction from animals: no cross-entity lock order inversion.
        return transaction.execute { _ ->
            limits()
            val rows = jdbc.queryForList(
                "SELECT id,search_revision,name,address FROM pawbridge_animal.shelters s " +
                    "WHERE " + pending + " ORDER BY id LIMIT ? FOR UPDATE OF s SKIP LOCKED",
                *parameters,
            )
            for (row in rows) {
                SearchDocumentWriter.writeShelter(jdbc, analyzer, row)
            }
            rows.size
        }!!
    }

    private fun limits() {
        jdbc.execute("SET LOCAL lock_timeout='1s'")
        jdbc.execute("SET LOCAL statement_timeout='4s'")
    }

    private companion object {
        private fun validate(limit: Int) {
            if (limit < 1 || limit > 100) {
                throw IllegalArgumentException("Projection page must contain 1 to 100 rows")
            }
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                throw IllegalStateException("Projection maintenance requires its own transaction")
            }
        }
    }
}
