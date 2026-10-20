package com.pawbridge.backend.store.internal.directorder.infrastructure;

import com.pawbridge.backend.store.internal.catalog.infrastructure.KoreanSearchTerms;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Selected original search write/delete SQL; Root supplies JDBC from the same writer DataSource as JpaTM. */
final class PostgresqlSearchDocuments {
    private final NamedParameterJdbcOperations jdbc;
    private final KoreanSearchTerms terms;
    PostgresqlSearchDocuments(NamedParameterJdbcOperations jdbc, KoreanSearchTerms terms) { this.jdbc=jdbc;this.terms=terms; }

    void write(long id,long skuId,String name,String option) {
        requireTransaction();
        jdbc.update("""
                INSERT INTO pawbridge_store.product_search_documents(product_id,sku_id,source_name,source_option,analyzer_version,tokens)
                VALUES(:id,:skuId,:name,:option,:version,
                    setweight(to_tsvector('simple',:nameTerms),'A') || setweight(to_tsvector('simple',:optionTerms),'B'))
                ON CONFLICT(product_id) DO UPDATE SET sku_id=excluded.sku_id,source_name=excluded.source_name,
                    source_option=excluded.source_option,analyzer_version=excluded.analyzer_version,tokens=excluded.tokens
                """,Map.of("id",id,"skuId",skuId,"name",name,"option",option,"version",KoreanSearchTerms.VERSION,
                    "nameTerms",terms.document(name),"optionTerms",terms.document(option)));
    }
    void delete(long id) {
        requireTransaction();jdbc.update("DELETE FROM pawbridge_store.product_search_documents WHERE product_id=:id",Map.of("id",id));
    }
    private static void requireTransaction() {
        if(!TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalStateException("Source and search documents require an active writer transaction");
    }
}
