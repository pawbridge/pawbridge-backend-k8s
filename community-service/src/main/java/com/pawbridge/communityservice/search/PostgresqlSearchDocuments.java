package com.pawbridge.communityservice.search;

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

    public void write(long id,String title,String content) {
        requireTransaction();
        jdbc.update("""
                INSERT INTO post_search_documents(post_id,title_hash,content_hash,analyzer_version,tokens)
                VALUES(:id,md5(:title),md5(:content),:version,
                    setweight(to_tsvector('simple',:titleTerms),'A') || setweight(to_tsvector('simple',:contentTerms),'B'))
                ON CONFLICT(post_id) DO UPDATE SET title_hash=excluded.title_hash,content_hash=excluded.content_hash,
                    analyzer_version=excluded.analyzer_version,tokens=excluded.tokens
                """,Map.of("id",id,"title",title,"content",content,"version",KoreanSearchTerms.VERSION,
                    "titleTerms",terms.document(title),"contentTerms",terms.document(content)));
    }
    public void delete(long id) {
        requireTransaction();jdbc.update("DELETE FROM post_search_documents WHERE post_id=:id",Map.of("id",id));
    }
    @Transactional(timeout=10)
    public int rebuildPage(int limit) {
        requireTransaction();
        if(limit<1 || limit>100) throw new IllegalArgumentException("Recovery page must be 1..100");
        List<Map<String,Object>> rows=jdbc.queryForList("""
                SELECT p.post_id,p.title,p.content FROM posts p
                LEFT JOIN post_search_documents d ON d.post_id=p.post_id
                WHERE p.deleted_at IS NULL AND (d.post_id IS NULL OR d.title_hash<>md5(p.title)
                    OR d.content_hash<>md5(p.content) OR d.analyzer_version<>:version)
                ORDER BY p.post_id LIMIT :limit FOR UPDATE OF p SKIP LOCKED
                """,Map.of("version",KoreanSearchTerms.VERSION,"limit",limit));
        for(Map<String,Object> row:rows) write(((Number)row.get("post_id")).longValue(),(String)row.get("title"),(String)row.get("content"));
        return rows.size();
    }

}
