package com.pawbridge.backend.store.internal.directorder.infrastructure

import com.pawbridge.backend.store.internal.catalog.infrastructure.KoreanSearchTerms
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations
import org.springframework.transaction.support.TransactionSynchronizationManager

/** Selected original search write/delete SQL; Root supplies JDBC from the same writer DataSource as JpaTM. */
internal class PostgresqlSearchDocuments internal constructor(
    private val jdbc: NamedParameterJdbcOperations,
    private val terms: KoreanSearchTerms,
) {

    // name and option stay nullable: the Java original rejected null only through Map.of, after the transaction check.
    internal fun write(id: Long, skuId: Long, name: String?, option: String?) {
        requireTransaction()
        jdbc.update(
            WRITE_SQL,
            java.util.Map.of<String, Any?>(
                "id", id,
                "skuId", skuId,
                "name", name,
                "option", option,
                "version", KoreanSearchTerms.VERSION,
                "nameTerms", terms.document(name),
                "optionTerms", terms.document(option),
            ),
        )
    }

    internal fun delete(id: Long) {
        requireTransaction()
        jdbc.update(DELETE_SQL, java.util.Map.of<String, Any?>("id", id))
    }

    private companion object {
        private val WRITE_SQL = """
            INSERT INTO pawbridge_store.product_search_documents(product_id,sku_id,source_name,source_option,analyzer_version,tokens)
            VALUES(:id,:skuId,:name,:option,:version,
                setweight(to_tsvector('simple',:nameTerms),'A') || setweight(to_tsvector('simple',:optionTerms),'B'))
            ON CONFLICT(product_id) DO UPDATE SET sku_id=excluded.sku_id,source_name=excluded.source_name,
                source_option=excluded.source_option,analyzer_version=excluded.analyzer_version,tokens=excluded.tokens
        """.trimIndent() + "\n"

        private const val DELETE_SQL = "DELETE FROM pawbridge_store.product_search_documents WHERE product_id=:id"

        private fun requireTransaction() {
            if (!TransactionSynchronizationManager.isActualTransactionActive() ||
                TransactionSynchronizationManager.isCurrentTransactionReadOnly()
            ) {
                throw IllegalStateException("Source and search documents require an active writer transaction")
            }
        }
    }
}
