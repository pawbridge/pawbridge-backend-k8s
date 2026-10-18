package com.pawbridge.animalservice.search;

import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Bounded import/repair transactions; never called inside an API/Batch business transaction.
 * A zero-row page may mean rows are locked elsewhere, not that the entire corpus is ready.
 */
public final class PostgresqlSearchProjector {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final KoreanSearchAnalyzer analyzer;

    public PostgresqlSearchProjector(DataSource source,KoreanSearchAnalyzer analyzer) {
        this.jdbc=new JdbcTemplate(source);jdbc.setQueryTimeout(5);
        this.transaction=new TransactionTemplate(new JdbcTransactionManager(source));transaction.setTimeout(5);
        this.analyzer=analyzer;
    }

    public int refreshAnimals(int limit) {
        return refreshAnimals(limit,true);
    }

    public int refreshDirtyAnimals(int limit) {
        return refreshAnimals(limit,false);
    }

    private int refreshAnimals(int limit, boolean audit) {
        validate(limit);
        String pending=audit?"search_dirty OR NOT EXISTS (SELECT 1 FROM animal_search_documents d "
                +"WHERE d.animal_id=a.id AND d.source_revision=a.search_revision AND d.analyzer_version=?)":"search_dirty";
        Object[] parameters=audit?new Object[]{KoreanSearchAnalyzer.VERSION,limit}:new Object[]{limit};
        return transaction.execute(status -> {
            limits();
            // Lock source first: UPDATE/DELETE and other projectors use the same lock order.
            List<Map<String,Object>> rows=jdbc.queryForList("SELECT id,search_revision,breed,color,special_mark,description,happen_place "
                    +"FROM animals a WHERE "+pending
                    +" ORDER BY id LIMIT ? FOR UPDATE OF a SKIP LOCKED",parameters);
            for(Map<String,Object> row:rows) {
                SearchDocumentWriter.writeAnimal(jdbc,analyzer,row);
            }
            return rows.size();
        });
    }

    public int refreshShelters(int limit) {
        return refreshShelters(limit,true);
    }

    public int refreshDirtyShelters(int limit) {
        return refreshShelters(limit,false);
    }

    private int refreshShelters(int limit, boolean audit) {
        validate(limit);
        String pending=audit?"search_dirty OR NOT EXISTS (SELECT 1 FROM shelter_search_documents d "
                +"WHERE d.shelter_id=s.id AND d.source_revision=s.search_revision AND d.analyzer_version=?)":"search_dirty";
        Object[] parameters=audit?new Object[]{KoreanSearchAnalyzer.VERSION,limit}:new Object[]{limit};
        // Separate transaction from animals: no cross-entity lock order inversion.
        return transaction.execute(status -> {
            limits();
            List<Map<String,Object>> rows=jdbc.queryForList("SELECT id,search_revision,name,address FROM shelters s "
                    +"WHERE "+pending+" ORDER BY id LIMIT ? FOR UPDATE OF s SKIP LOCKED",parameters);
            for(Map<String,Object> row:rows) {
                SearchDocumentWriter.writeShelter(jdbc,analyzer,row);
            }
            return rows.size();
        });
    }

    private void limits() {
        jdbc.execute("SET LOCAL lock_timeout='1s'");
        jdbc.execute("SET LOCAL statement_timeout='4s'");
    }
    private static void validate(int limit) {
        if(limit<1 || limit>100) throw new IllegalArgumentException("Projection page must contain 1 to 100 rows");
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Projection maintenance requires its own transaction");
    }
}
