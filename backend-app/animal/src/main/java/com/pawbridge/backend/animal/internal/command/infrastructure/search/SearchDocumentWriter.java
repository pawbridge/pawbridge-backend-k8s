package com.pawbridge.backend.animal.internal.command.infrastructure.search;
import com.pawbridge.backend.animal.internal.discovery.infrastructure.KoreanSearchAnalyzer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Search projection writes always join the same Animal JPA writer transaction. */
public final class SearchDocumentWriter {
    private final DataSource source;
    private final KoreanSearchAnalyzer analyzer;
    public SearchDocumentWriter(DataSource source, KoreanSearchAnalyzer analyzer) {
        this.source = Objects.requireNonNull(source);
        this.analyzer = Objects.requireNonNull(analyzer);
    }
    public DataSource dataSource() { return source; }

    public void animal(long id) {
        JdbcTemplate jdbc=transactionJdbc();
        for (Map<String,Object> row:jdbc.queryForList(
                "SELECT id,search_revision,breed,color,special_mark,description,happen_place FROM pawbridge_animal.animals "
                +"WHERE id=? AND search_dirty FOR UPDATE",id)) writeAnimal(jdbc,analyzer,row);
    }

    public void shelter(long id) {
        JdbcTemplate jdbc = transactionJdbc();
        for (Map<String,Object> row : jdbc.queryForList(
                "SELECT id,search_revision,name,address FROM pawbridge_animal.shelters WHERE id=? AND search_dirty FOR UPDATE", id))
            writeShelter(jdbc, analyzer, row);
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

    static void writeAnimal(JdbcTemplate jdbc, KoreanSearchAnalyzer analyzer, Map<String,Object> row) {
        KoreanSearchAnalyzer.AnimalTerms terms;
        try {
            terms=analyzer.animalTerms((String)row.get("breed"),(String)row.get("color"),
                    (String)row.get("special_mark"),(String)row.get("description"),(String)row.get("happen_place"));
        } catch(IOException failure) { throw new UncheckedIOException(failure); }
        jdbc.update("INSERT INTO pawbridge_animal.animal_search_documents(animal_id,source_revision,analyzer_version,tokens,breed_tokens,relation_tokens) "
                +"VALUES (?,?,?,setweight(to_tsvector('simple',?),'A')||setweight(to_tsvector('simple',?),'A')||"
                +"setweight(to_tsvector('simple',?),'B')||setweight(to_tsvector('simple',?),'B')||"
                +"setweight(to_tsvector('simple',?),'C'),to_tsvector('simple',?),to_tsvector('simple',?)) "
                +"ON CONFLICT(animal_id) DO UPDATE SET source_revision=EXCLUDED.source_revision,analyzer_version=EXCLUDED.analyzer_version,"
                +"tokens=EXCLUDED.tokens,breed_tokens=EXCLUDED.breed_tokens,relation_tokens=EXCLUDED.relation_tokens",
                row.get("id"),row.get("search_revision"),KoreanSearchAnalyzer.VERSION,
                terms.mark(),terms.breed(),terms.color(),terms.description(),
                terms.place(),terms.breed(),terms.relations());
        jdbc.update("UPDATE pawbridge_animal.animals SET search_dirty=false WHERE id=?",row.get("id"));
    }
    static void writeShelter(JdbcTemplate jdbc, KoreanSearchAnalyzer analyzer, Map<String,Object> row) {
        KoreanSearchAnalyzer.ShelterTerms terms;
        try { terms=analyzer.shelterTerms((String)row.get("name"),(String)row.get("address")); }
        catch(IOException failure) { throw new UncheckedIOException(failure); }
        jdbc.update("INSERT INTO pawbridge_animal.shelter_search_documents(shelter_id,source_revision,analyzer_version,tokens) "
                +"VALUES (?,?,?,setweight(to_tsvector('simple',?),'D')||setweight(to_tsvector('simple',?),'D')) "
                +"ON CONFLICT(shelter_id) DO UPDATE SET source_revision=EXCLUDED.source_revision,analyzer_version=EXCLUDED.analyzer_version,tokens=EXCLUDED.tokens",
                row.get("id"),row.get("search_revision"),KoreanSearchAnalyzer.VERSION,terms.name(),terms.address());
        jdbc.update("UPDATE pawbridge_animal.shelters SET search_dirty=false WHERE id=?",row.get("id"));
    }
}
