package com.pawbridge.backend.store.internal.directorder.infrastructure;

import com.pawbridge.backend.store.internal.catalog.infrastructure.KoreanSearchTerms;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Original Store rebuildPage SQL; Root supplies writer JDBC and the original 10-second writer transaction. */
public final class JdbcProductSearchRebuild {
    private static final String PAGE = """
            SELECT p.id FROM pawbridge_store.products p JOIN pawbridge_store.product_search_source s ON s.product_id=p.id
            LEFT JOIN pawbridge_store.product_search_documents d ON d.product_id=p.id
            WHERE p.status='ACTIVE' AND (d.product_id IS NULL OR d.sku_id<>s.sku_id
                OR d.source_name<>s.name OR d.source_option<>s.option_name OR d.analyzer_version<>:version)
            ORDER BY p.id LIMIT :limit FOR UPDATE OF p SKIP LOCKED
            """;
    private static final String SOURCE =
            "SELECT sku_id,name,option_name FROM pawbridge_store.product_search_source WHERE product_id=:id";

    private final NamedParameterJdbcOperations jdbc;
    private final TransactionOperations transactions;
    private final PostgresqlSearchDocuments documents;

    public JdbcProductSearchRebuild(NamedParameterJdbcOperations jdbc, TransactionOperations transactions, KoreanSearchTerms terms) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.documents = new PostgresqlSearchDocuments(jdbc, terms);
    }

    public int rebuildPage(int limit) {
        return transactions.execute(status -> {
            if (!TransactionSynchronizationManager.isActualTransactionActive()) {
                throw new IllegalStateException("Source and search documents require the same transaction");
            }
            if (limit < 1 || limit > 100) throw new IllegalArgumentException("Recovery page must be 1..100");
            List<Long> ids = jdbc.queryForList(PAGE, Map.of("version", KoreanSearchTerms.VERSION, "limit", limit), Long.class);
            for (Long id : ids) {
                Map<String, Object> row = jdbc.queryForMap(SOURCE, Map.of("id", id));
                documents.write(id, ((Number) row.get("sku_id")).longValue(), (String) row.get("name"),
                        (String) row.get("option_name"));
            }
            return ids.size();
        });
    }
}
