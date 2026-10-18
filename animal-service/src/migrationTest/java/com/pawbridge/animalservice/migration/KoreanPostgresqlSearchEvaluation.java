package com.pawbridge.animalservice.migration;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Fixed-fixture quality experiment, not a production index updater. */
final class KoreanPostgresqlSearchEvaluation implements AutoCloseable {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final KoreanSearchEvaluation analyzer = new KoreanSearchEvaluation();

    KoreanPostgresqlSearchEvaluation(JdbcTemplate jdbc) throws IOException {
        this.jdbc=jdbc; this.named=new NamedParameterJdbcTemplate(jdbc);
    }

    long prepare() throws IOException {
        // The caller has already checked migration_test_guard and loaded the immutable fixture.
        jdbc.execute("DROP TABLE IF EXISTS korean_search_evaluation");
        jdbc.execute("CREATE TABLE korean_search_evaluation(animal_id bigint PRIMARY KEY REFERENCES animals(id) ON DELETE CASCADE,"
                + "tokens tsvector NOT NULL,breed_tokens tsvector NOT NULL,relation_tokens tsvector NOT NULL)");
        long start=System.nanoTime();
        long cursor=0;
        while (true) {
            List<Map<String,Object>> rows=jdbc.queryForList("SELECT a.id,a.breed,a.color,a.special_mark,a.description,"
                    +"a.happen_place,s.name,s.address FROM animals a JOIN shelters s ON s.id=a.shelter_id WHERE a.id>? ORDER BY a.id LIMIT 100",cursor);
            if (rows.isEmpty()) break;
            List<Object[]> batch=new ArrayList<>();
            for (Map<String,Object> row : rows) {
                List<Object> parameters=new ArrayList<>();parameters.add(row.get("id"));
                for (String field:List.of("special_mark","breed","color","description","happen_place","name","address","breed"))
                    parameters.add(String.join(" ",analyzer.tokens((String)row.get(field))));
                List<String> relations=new ArrayList<>();
                // Do not fabricate a relation across fields or from shelter/location text.
                for (String field:List.of("special_mark","description"))
                    relations.addAll(analyzer.relations((String)row.get(field)));
                parameters.add(String.join(" ",relations));
                batch.add(parameters.toArray());
                cursor=((Number)row.get("id")).longValue();
            }
            jdbc.batchUpdate("INSERT INTO korean_search_evaluation VALUES (?,"
                    +"setweight(to_tsvector('simple',?),'A')||setweight(to_tsvector('simple',?),'A')||"
                    +"setweight(to_tsvector('simple',?),'B')||setweight(to_tsvector('simple',?),'B')||"
                    +"setweight(to_tsvector('simple',?),'C')||setweight(to_tsvector('simple',?),'D')||"
                    +"setweight(to_tsvector('simple',?),'D'),to_tsvector('simple',?),to_tsvector('simple',?))",batch);
        }
        jdbc.execute("CREATE INDEX korean_search_evaluation_gin ON korean_search_evaluation USING gin(tokens)");
        jdbc.execute("CREATE INDEX korean_search_evaluation_breed_gin ON korean_search_evaluation USING gin(breed_tokens)");
        jdbc.execute("CREATE INDEX korean_search_evaluation_relation_gin ON korean_search_evaluation USING gin(relation_tokens)");
        jdbc.execute("ANALYZE korean_search_evaluation");
        return (System.nanoTime()-start)/1_000_000;
    }

    Result search(String kind,String text,int limit) throws IOException {
        boolean breed=kind.equals("breed");
        com.pawbridge.animalservice.search.KoreanSearchAnalyzer.Relation relation=breed?null:analyzer.queryRelation(text);
        List<String> tokens=relation==null?analyzer.tokens(text):relation.tokens();
        if (tokens.isEmpty()) return new Result(List.of(),0,tokens);
        String vector=breed?"d.breed_tokens":"d.tokens";
        String match=vector+" @@ plainto_tsquery('simple',:tokens)";
        if (breed) match="("+match+" OR public.word_similarity(:literal,lower(a.breed))>=0.5)";
        if (relation!=null) match+=" AND d.relation_tokens @@ plainto_tsquery('simple',:relation)";
        String from=" FROM korean_search_evaluation d JOIN animals a ON a.id=d.animal_id WHERE a.status IN ('NOTICE','PROTECT') AND "+match;
        Map<String,Object> values=new LinkedHashMap<>(Map.of("tokens",String.join(" ",tokens),"literal",text.toLowerCase(java.util.Locale.ROOT),"limit",limit));
        if (relation!=null) values.put("relation",relation.key());
        long count=named.queryForObject("SELECT count(*)"+from,values,Long.class);
        String score="ts_rank_cd("+vector+",plainto_tsquery('simple',:tokens),32)";
        if (breed) score+="+CASE WHEN lower(a.breed)=:literal THEN 100 ELSE 0 END";
        List<Long> ids=named.queryForList("SELECT a.id"+from+" ORDER BY "+score+" DESC,a.created_at DESC,a.id ASC LIMIT :limit",values,Long.class);
        return new Result(ids,count,tokens);
    }

    Map<String,Object> compare(JsonNode input) throws IOException {
        long prepareMillis=prepare();
        List<Map<String,Object>> queries=new ArrayList<>();
        for (JsonNode reference:input.path("queries")) {
            String kind=reference.path("kind").asText(), term=reference.path("term").asText();
            List<Long> times=new ArrayList<>();
            for (int i=0;i<4;i++) {
                long start=System.nanoTime();search(kind,term,20);times.add((System.nanoTime()-start)/1_000_000);
            }
            Result result=search(kind,term,2000);
            Map<String,Object> row=new LinkedHashMap<>();row.put("kind",kind);row.put("term",term);
            row.put("tokens",result.tokens());row.put("count",result.count());row.put("ids",result.ids());
            row.put("countAndPageMs",times);queries.add(row);
            System.out.printf("NORI_PG %s count=%d tokens=%s ms=%s%n",term,result.count(),result.tokens(),times);
        }
        return Map.of("analyzer","Lucene Nori9.12.3 DISCARD; bounded synonyms; simple noun-predicate queries require field-local clause/polarity evidence",
                "projectionPrepareMs",prepareMillis,"projectionBytes",jdbc.queryForObject("SELECT pg_total_relation_size('korean_search_evaluation')",Long.class),
                "queries",queries,"runtimeAdopted",false,
                "limits",List.of("Projection lifecycle is not wired to writes", "Strict analyzed-term coverage, not ES BM25 parity", "Predicate evidence is a bounded rule for simple noun-predicate queries, not general semantic parsing", "Complex queries, coordination and double negation need separate evaluation", "Preparation timing excludes analyzer initialization"));
    }

    record Result(List<Long> ids,long count,List<String> tokens) {}
    @Override public void close() { analyzer.close(); }
}
