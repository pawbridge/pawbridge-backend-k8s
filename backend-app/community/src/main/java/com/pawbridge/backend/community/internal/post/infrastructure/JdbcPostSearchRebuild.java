package com.pawbridge.backend.community.internal.post.infrastructure;

import com.pawbridge.backend.community.internal.post.infrastructure.search.KoreanSearchTerms;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class JdbcPostSearchRebuild {
    private static final String PAGE = """
            SELECT p.post_id,p.title,p.content FROM pawbridge_community.posts p
            LEFT JOIN pawbridge_community.post_search_documents d ON d.post_id=p.post_id
            WHERE p.deleted_at IS NULL AND (d.post_id IS NULL OR d.title_hash<>md5(p.title)
                OR d.content_hash<>md5(p.content) OR d.analyzer_version<>?)
            ORDER BY p.post_id LIMIT ? FOR UPDATE OF p SKIP LOCKED
            """;

    private final JdbcOperations jdbc;
    private final TransactionOperations transactions;
    private final JdbcPostWriter projection;

    public JdbcPostSearchRebuild(JdbcOperations jdbc, TransactionOperations transactions, JdbcPostWriter projection) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.projection = projection;
    }

    public int rebuildPage(int limit) {
        return transactions.execute(status -> {
            if (!TransactionSynchronizationManager.isActualTransactionActive()) {
                throw new IllegalStateException("Source and search documents require the same transaction");
            }
            if (limit < 1 || limit > 100) throw new IllegalArgumentException("Recovery page must be 1..100");
            List<Map<String, Object>> rows = jdbc.queryForList(PAGE, KoreanSearchTerms.VERSION, limit);
            for (Map<String, Object> row : rows) {
                projection.writeSearchDocument(((Number) row.get("post_id")).longValue(),
                        (String) row.get("title"), (String) row.get("content"));
            }
            return rows.size();
        });
    }
}
