package com.pawbridge.backend.community.internal.post.infrastructure

import com.pawbridge.backend.community.internal.post.infrastructure.search.KoreanSearchTerms
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionSynchronizationManager

class JdbcPostSearchRebuild(
    private val jdbc: JdbcOperations,
    private val transactions: TransactionOperations,
    private val projection: JdbcPostWriter,
) {

    fun rebuildPage(limit: Int): Int =
        transactions.execute {
            if (!TransactionSynchronizationManager.isActualTransactionActive()) {
                throw IllegalStateException("Source and search documents require the same transaction")
            }
            if (limit < 1 || limit > 100) {
                throw IllegalArgumentException("Recovery page must be 1..100")
            }
            val rows: List<Map<String, Any>> = jdbc.queryForList(PAGE, KoreanSearchTerms.VERSION, limit)
            for (row in rows) {
                projection.writeSearchDocument(
                    (row["post_id"] as Number).toLong(),
                    row["title"] as String?,
                    row["content"] as String?,
                )
            }
            rows.size
        }!!

    private companion object {
        private val PAGE = """
            SELECT p.post_id,p.title,p.content FROM pawbridge_community.posts p
            LEFT JOIN pawbridge_community.post_search_documents d ON d.post_id=p.post_id
            WHERE p.deleted_at IS NULL AND (d.post_id IS NULL OR d.title_hash<>md5(p.title)
                OR d.content_hash<>md5(p.content) OR d.analyzer_version<>?)
            ORDER BY p.post_id LIMIT ? FOR UPDATE OF p SKIP LOCKED
        """.trimIndent() + "\n"
    }
}
