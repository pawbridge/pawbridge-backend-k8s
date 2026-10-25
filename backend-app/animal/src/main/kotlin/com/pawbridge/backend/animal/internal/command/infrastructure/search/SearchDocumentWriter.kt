package com.pawbridge.backend.animal.internal.command.infrastructure.search

import com.pawbridge.backend.animal.internal.discovery.infrastructure.KoreanSearchAnalyzer
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.io.IOException
import java.io.UncheckedIOException
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource

/** Search projection writes always join the same Animal JPA writer transaction. */
class SearchDocumentWriter(
    private val source: DataSource,
    private val analyzer: KoreanSearchAnalyzer,
) {
    fun dataSource(): DataSource = source

    fun animal(id: Long) {
        val jdbc = transactionJdbc()
        val rows = jdbc.queryForList(
            "SELECT id,search_revision,breed,color,special_mark,description,happen_place FROM pawbridge_animal.animals " +
                "WHERE id=? AND search_dirty FOR UPDATE",
            id,
        )
        for (row in rows) {
            writeAnimal(jdbc, analyzer, row)
        }
    }

    fun shelter(id: Long) {
        val jdbc = transactionJdbc()
        val rows = jdbc.queryForList(
            "SELECT id,search_revision,name,address FROM pawbridge_animal.shelters WHERE id=? AND search_dirty FOR UPDATE",
            id,
        )
        for (row in rows) {
            writeShelter(jdbc, analyzer, row)
        }
    }

    /** The directory collector owns a raw JDBC transaction; never borrow a second connection here. */
    @Throws(SQLException::class)
    fun shelter(connection: Connection, careRegNo: String?) {
        if (connection.autoCommit) {
            throw IllegalStateException("Search writes require the source transaction")
        }
        val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
        jdbc.queryTimeout = 5
        val rows = jdbc.queryForList(
            "SELECT id,search_revision,name,address FROM pawbridge_animal.shelters WHERE care_reg_no=? AND search_dirty FOR UPDATE",
            careRegNo,
        )
        for (row in rows) {
            writeShelter(jdbc, analyzer, row)
        }
    }

    private fun transactionJdbc(): JdbcTemplate {
        if (!TransactionSynchronizationManager.isActualTransactionActive() ||
            !TransactionSynchronizationManager.hasResource(source) ||
            TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        ) {
            throw IllegalStateException("Search writes must join the source database write transaction")
        }
        val jdbc = JdbcTemplate(source)
        jdbc.queryTimeout = 5
        return jdbc
    }

    companion object {
        // Package-private in Java; the same-package Java tests call these statically.
        @JvmStatic
        fun writeAnimal(jdbc: JdbcTemplate, analyzer: KoreanSearchAnalyzer, row: Map<String, Any?>) {
            val terms = try {
                analyzer.animalTerms(
                    row["breed"] as String?,
                    row["color"] as String?,
                    row["special_mark"] as String?,
                    row["description"] as String?,
                    row["happen_place"] as String?,
                )
            } catch (failure: IOException) {
                throw UncheckedIOException(failure)
            }
            jdbc.update(
                "INSERT INTO pawbridge_animal.animal_search_documents(animal_id,source_revision,analyzer_version,tokens,breed_tokens,relation_tokens) " +
                    "VALUES (?,?,?,setweight(to_tsvector('simple',?),'A')||setweight(to_tsvector('simple',?),'A')||" +
                    "setweight(to_tsvector('simple',?),'B')||setweight(to_tsvector('simple',?),'B')||" +
                    "setweight(to_tsvector('simple',?),'C'),to_tsvector('simple',?),to_tsvector('simple',?)) " +
                    "ON CONFLICT(animal_id) DO UPDATE SET source_revision=EXCLUDED.source_revision,analyzer_version=EXCLUDED.analyzer_version," +
                    "tokens=EXCLUDED.tokens,breed_tokens=EXCLUDED.breed_tokens,relation_tokens=EXCLUDED.relation_tokens",
                row["id"],
                row["search_revision"],
                KoreanSearchAnalyzer.VERSION,
                terms.mark,
                terms.breed,
                terms.color,
                terms.description,
                terms.place,
                terms.breed,
                terms.relations,
            )
            jdbc.update("UPDATE pawbridge_animal.animals SET search_dirty=false WHERE id=?", row["id"])
        }

        @JvmStatic
        fun writeShelter(jdbc: JdbcTemplate, analyzer: KoreanSearchAnalyzer, row: Map<String, Any?>) {
            val terms = try {
                analyzer.shelterTerms(row["name"] as String?, row["address"] as String?)
            } catch (failure: IOException) {
                throw UncheckedIOException(failure)
            }
            jdbc.update(
                "INSERT INTO pawbridge_animal.shelter_search_documents(shelter_id,source_revision,analyzer_version,tokens) " +
                    "VALUES (?,?,?,setweight(to_tsvector('simple',?),'D')||setweight(to_tsvector('simple',?),'D')) " +
                    "ON CONFLICT(shelter_id) DO UPDATE SET source_revision=EXCLUDED.source_revision,analyzer_version=EXCLUDED.analyzer_version,tokens=EXCLUDED.tokens",
                row["id"],
                row["search_revision"],
                KoreanSearchAnalyzer.VERSION,
                terms.name,
                terms.address,
            )
            jdbc.update("UPDATE pawbridge_animal.shelters SET search_dirty=false WHERE id=?", row["id"])
        }
    }
}
