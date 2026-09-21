package com.pawbridge.storeservice.search;

import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@ConditionalOnProperty(name="pawbridge.search.backend",havingValue="postgresql")
public class PostgresqlSearchDocuments {
    private final NamedParameterJdbcTemplate jdbc;
    private final KoreanSearchTerms terms;
    public PostgresqlSearchDocuments(DataSource source,KoreanSearchTerms terms) {
        this.jdbc=new NamedParameterJdbcTemplate(source);this.terms=terms;
        this.jdbc.getJdbcTemplate().setQueryTimeout(5);
    }
    private void requireTransaction() {
        if(!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Source and search documents require the same transaction");
    }

    public void write(long id,long skuId,String name,String option) {
        requireTransaction();
        jdbc.update("""
                INSERT INTO product_search_documents(product_id,sku_id,source_name,source_option,analyzer_version,tokens)
                VALUES(:id,:skuId,:name,:option,:version,
                    setweight(to_tsvector('simple',:nameTerms),'A') || setweight(to_tsvector('simple',:optionTerms),'B'))
                ON CONFLICT(product_id) DO UPDATE SET sku_id=excluded.sku_id,source_name=excluded.source_name,
                    source_option=excluded.source_option,analyzer_version=excluded.analyzer_version,tokens=excluded.tokens
                """,Map.of("id",id,"skuId",skuId,"name",name,"option",option,"version",KoreanSearchTerms.VERSION,
                    "nameTerms",terms.document(name),"optionTerms",terms.document(option)));
    }
    public void delete(long id) {
        requireTransaction();jdbc.update("DELETE FROM product_search_documents WHERE product_id=:id",Map.of("id",id));
    }
    @Transactional(timeout=10)
    public int rebuildPage(int limit) {
        requireTransaction();
        if(limit<1 || limit>100) throw new IllegalArgumentException("Recovery page must be 1..100");
        List<Long> ids=jdbc.queryForList("""
                SELECT p.id FROM products p JOIN product_search_source s ON s.product_id=p.id
                LEFT JOIN product_search_documents d ON d.product_id=p.id
                WHERE p.status='ACTIVE' AND (d.product_id IS NULL OR d.sku_id<>s.sku_id
                    OR d.source_name<>s.name OR d.source_option<>s.option_name OR d.analyzer_version<>:version)
                ORDER BY p.id LIMIT :limit FOR UPDATE OF p SKIP LOCKED
                """,Map.of("version",KoreanSearchTerms.VERSION,"limit",limit),Long.class);
        for(Long id:ids) {
            Map<String,Object> row=jdbc.queryForMap("SELECT sku_id,name,option_name FROM product_search_source WHERE product_id=:id",Map.of("id",id));
            write(id,((Number)row.get("sku_id")).longValue(),(String)row.get("name"),(String)row.get("option_name"));
        }
        return ids.size();
    }

}
