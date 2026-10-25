package com.pawbridge.backend.store.internal.directorder.infrastructure

import com.pawbridge.backend.store.internal.catalog.infrastructure.KoreanSearchTerms
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionSynchronizationManager

/** Original Store rebuildPage SQL; Root supplies writer JDBC and the original 10-second writer transaction. */
class JdbcProductSearchRebuild(
    private val jdbc: NamedParameterJdbcOperations,
    private val transactions: TransactionOperations,
    terms: KoreanSearchTerms,
) {

    private val documents = PostgresqlSearchDocuments(jdbc, terms)

    fun rebuildPage(limit: Int): Int =
        transactions.execute {
            if (!TransactionSynchronizationManager.isActualTransactionActive()) {
                throw IllegalStateException("Source and search documents require the same transaction")
            }
            if (limit < 1 || limit > 100) {
                throw IllegalArgumentException("Recovery page must be 1..100")
            }
            val ids = jdbc.queryForList(
                PAGE,
                java.util.Map.of<String, Any>("version", KoreanSearchTerms.VERSION, "limit", limit),
                Long::class.javaObjectType,
            )
            for (id in ids) {
                val row = jdbc.queryForMap(SOURCE, java.util.Map.of<String, Any>("id", id))
                documents.write(
                    id,
                    (row["sku_id"] as Number).toLong(),
                    row["name"] as String?,
                    row["option_name"] as String?,
                )
            }
            ids.size
        }!!

    private companion object {
        private val PAGE = """
            SELECT p.id FROM pawbridge_store.products p JOIN pawbridge_store.product_search_source s ON s.product_id=p.id
            LEFT JOIN pawbridge_store.product_search_documents d ON d.product_id=p.id
            WHERE p.status='ACTIVE' AND (d.product_id IS NULL OR d.sku_id<>s.sku_id
                OR d.source_name<>s.name OR d.source_option<>s.option_name OR d.analyzer_version<>:version)
            ORDER BY p.id LIMIT :limit FOR UPDATE OF p SKIP LOCKED
        """.trimIndent() + "\n"

        private const val SOURCE =
            "SELECT sku_id,name,option_name FROM pawbridge_store.product_search_source WHERE product_id=:id"
    }
}
