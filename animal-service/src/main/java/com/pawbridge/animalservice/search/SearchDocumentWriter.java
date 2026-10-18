package com.pawbridge.animalservice.search;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Writes derived search rows using the caller's transaction. Inactive on the existing ES backend. */
@Component
public final class SearchDocumentWriter {
    private final DataSource source;
    private final KoreanSearchAnalyzer analyzer;

    public SearchDocumentWriter(DataSource source, Optional<KoreanSearchAnalyzer> analyzer) {
        this.source=source;
        this.analyzer=analyzer.orElse(null);
    }

    public boolean enabled() { return analyzer!=null; }

    public void animal(long id) {
        if (!enabled()) return;
        JdbcTemplate jdbc=transactionJdbc();
        for (Map<String,Object> row:jdbc.queryForList(
                "SELECT id,search_revision,breed,color,special_mark,description,happen_place FROM animals "
                +"WHERE id=? AND search_dirty FOR UPDATE",id)) writeAnimal(jdbc,analyzer,row);
    }

    public void shelter(long id) {
        if (!enabled()) return;
        refreshShelter(transactionJdbc(),"id",id);
    }

    /** The directory collector owns a raw JDBC transaction; never borrow a second connection here. */
    public void shelter(Connection connection, String careRegNo) throws SQLException {
        if (!enabled()) return;
        if (connection.getAutoCommit()) throw new IllegalStateException("Search writes require the source transaction");
        JdbcTemplate jdbc=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
        jdbc.setQueryTimeout(5);
        refreshShelter(jdbc,"care_reg_no",careRegNo);
    }

    private JdbcTemplate transactionJdbc() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(source)
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalStateException("Search writes must join the source database write transaction");
        JdbcTemplate jdbc=new JdbcTemplate(source);
        jdbc.setQueryTimeout(5);
        return jdbc;
    }

    private void refreshShelter(JdbcTemplate jdbc, String key, Object value) {
        // key is selected only from the two fixed identifiers above, never from user input.
        for (Map<String,Object> row:jdbc.queryForList("SELECT id,search_revision,name,address FROM shelters WHERE "
                +key+"=? AND search_dirty FOR UPDATE",value)) writeShelter(jdbc,analyzer,row);
    }

    static void writeAnimal(JdbcTemplate jdbc, KoreanSearchAnalyzer analyzer, Map<String,Object> row) {
        KoreanSearchAnalyzer.AnimalTerms terms;
        try {
            terms=analyzer.animalTerms((String)row.get("breed"),(String)row.get("color"),
                    (String)row.get("special_mark"),(String)row.get("description"),(String)row.get("happen_place"));
        } catch(IOException failure) { throw new UncheckedIOException(failure); }
        jdbc.update("INSERT INTO animal_search_documents(animal_id,source_revision,analyzer_version,tokens,breed_tokens,relation_tokens) "
                +"VALUES (?,?,?,setweight(to_tsvector('simple',?),'A')||setweight(to_tsvector('simple',?),'A')||"
                +"setweight(to_tsvector('simple',?),'B')||setweight(to_tsvector('simple',?),'B')||"
                +"setweight(to_tsvector('simple',?),'C'),to_tsvector('simple',?),to_tsvector('simple',?)) "
                +"ON CONFLICT(animal_id) DO UPDATE SET source_revision=EXCLUDED.source_revision,analyzer_version=EXCLUDED.analyzer_version,"
                +"tokens=EXCLUDED.tokens,breed_tokens=EXCLUDED.breed_tokens,relation_tokens=EXCLUDED.relation_tokens",
                row.get("id"),row.get("search_revision"),KoreanSearchAnalyzer.VERSION,
                terms.mark(),terms.breed(),terms.color(),terms.description(),
                terms.place(),terms.breed(),terms.relations());
        jdbc.update("UPDATE animals SET search_dirty=false WHERE id=?",row.get("id"));
    }

    static void writeShelter(JdbcTemplate jdbc, KoreanSearchAnalyzer analyzer, Map<String,Object> row) {
        KoreanSearchAnalyzer.ShelterTerms terms;
        try { terms=analyzer.shelterTerms((String)row.get("name"),(String)row.get("address")); }
        catch(IOException failure) { throw new UncheckedIOException(failure); }
        jdbc.update("INSERT INTO shelter_search_documents(shelter_id,source_revision,analyzer_version,tokens) "
                +"VALUES (?,?,?,setweight(to_tsvector('simple',?),'D')||setweight(to_tsvector('simple',?),'D')) "
                +"ON CONFLICT(shelter_id) DO UPDATE SET source_revision=EXCLUDED.source_revision,analyzer_version=EXCLUDED.analyzer_version,tokens=EXCLUDED.tokens",
                row.get("id"),row.get("search_revision"),KoreanSearchAnalyzer.VERSION,terms.name(),terms.address());
        jdbc.update("UPDATE shelters SET search_dirty=false WHERE id=?",row.get("id"));
    }
}
