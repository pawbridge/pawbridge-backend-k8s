package com.pawbridge.animalservice.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.animalservice.dto.request.AnimalSearchRequest;
import com.pawbridge.animalservice.dto.response.AnimalResponse;
import com.pawbridge.animalservice.mapper.AnimalMapper;
import com.pawbridge.animalservice.service.PostgresqlAnimalQueryService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;

/** Opt-in observation tool over an externally captured public fixture; never a production data loader. */
public final class AnimalPostgresqlSearchComparison {
    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3 || (args.length==3 && !args[2].equals("nori")))
            throw new IllegalArgumentException("Fixture/report paths and optional nori mode required");
        String port = System.getenv("ANIMAL_PG_MIGRATION_TEST_PORT");
        if (port == null || !port.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Disposable port required");
        ObjectMapper json = new ObjectMapper();
        Path fixture = Path.of(args[0]);
        if (Files.size(fixture) > 16 * 1024 * 1024) throw new IllegalArgumentException("Fixture too large");
        JsonNode input = json.readTree(fixture.toFile());
        if (input.path("rows").size() < 1 || input.path("rows").size() > 2000)
            throw new IllegalArgumentException("Bounded public fixture required");
        String url = "jdbc:postgresql://127.0.0.1:" + port + "/pawbridge";
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url); config.setUsername("postgres"); config.setPassword("local_pg_test_only");
        config.setMaximumPoolSize(1); config.setMinimumIdle(0);
        try (HikariDataSource source = new HikariDataSource(config)) {
            JdbcTemplate jdbc = new JdbcTemplate(source); jdbc.setQueryTimeout(10);
            if (!jdbc.queryForList("SELECT marker FROM migration_test_guard.guard", String.class)
                    .equals(List.of("animal-pg-disposable"))) throw new IllegalStateException("Disposable guard required");
            jdbc.execute("DROP SCHEMA IF EXISTS pawbridge_animal CASCADE");
            jdbc.execute("CREATE SCHEMA pawbridge_animal");
            Map<String, String> env = AnimalPostgresqlMigrationTest.environment(url);
            env.put("ANIMAL_PG_MIGRATION_CONFIRM_TARGET", url);
            AnimalPostgresqlMigration.execute("migrate", AnimalPostgresqlMigration.Settings.from(env));
            jdbc.execute("SET search_path TO pawbridge_animal, public");
            Map<Long, JsonNode> rowsById = new HashMap<>();
            for (JsonNode row : input.path("rows")) {
                long id = row.path("id").asLong();
                if (id <= 0 || rowsById.put(id, row) != null) throw new IllegalArgumentException("Invalid fixture ids");
                jdbc.update("INSERT INTO shelters(id,created_at,care_reg_no,name,address) VALUES (?,now(),?,?,?) ON CONFLICT(id) DO NOTHING",
                        row.path("shelter_id").asLong(), "comparison-" + row.path("shelter_id").asLong(),
                        value(row,"shelter_name"), value(row,"shelter_address"));
                jdbc.update("INSERT INTO animals(id,created_at,api_source,apms_notice_no,species,breed,color,special_mark,description,"
                                + "happen_place,gender,neuter_status,birth_year,status,notice_start_date,notice_end_date,favorite_count,shelter_id) "
                                + "VALUES (?,?::timestamp,'APMS_ANIMAL',?,?,?,?,?,?,?,?,?,?,?,?::date,?::date,0,?)",
                        id,value(row,"created_at"),value(row,"apms_notice_no"),value(row,"species"),value(row,"breed"),
                        value(row,"color"),value(row,"special_mark"),value(row,"description"),value(row,"happen_place"),
                        value(row,"gender"),value(row,"neuter_status"),row.path("birth_year").isNull()?null:row.path("birth_year").asInt(),
                        value(row,"status"),value(row,"notice_start_date"),value(row,"notice_end_date"),row.path("shelter_id").asLong());
            }
            jdbc.execute("ANALYZE animals"); jdbc.execute("ANALYZE shelters");
            // The isolated fixture is immutable during comparison; no external services or JPA lookups are called.
            PostgresqlAnimalQueryService query = new PostgresqlAnimalQueryService(source,null,new AnimalMapper());
            List<Map<String,Object>> observations = new ArrayList<>();
            for (JsonNode reference : input.path("queries")) {
                String term = reference.path("term").asText();
                AnimalSearchRequest request = new AnimalSearchRequest();
                if (reference.path("kind").asText().equals("breed")) request.setBreed(term);
                else request.setKeyword(term);
                List<Long> ids = new ArrayList<>();
                List<Long> elapsed = new ArrayList<>();
                Page<AnimalResponse> first = null;
                for (int attempt = 0; attempt < 4; attempt++) {
                    long start = System.nanoTime();
                    first = query.searchAnimals(request,PageRequest.of(0,20,Sort.by("relevance")));
                    elapsed.add((System.nanoTime()-start)/1_000_000);
                }
                for (int page = 0; page < (first.getTotalElements()+99)/100; page++)
                    ids.addAll(query.searchAnimals(request,PageRequest.of(page,100,Sort.by("relevance")))
                            .stream().map(AnimalResponse::getId).toList());
                List<Long> esIds = new ArrayList<>();
                reference.path("ids").forEach(id -> esIds.add(id.asLong()));
                HashSet<Long> overlap = new HashSet<>(ids); overlap.retainAll(esIds);
                HashSet<Long> topOverlap = new HashSet<>(ids.stream().limit(20).toList());
                topOverlap.retainAll(esIds.stream().limit(20).toList());
                Map<String,Object> observation = new LinkedHashMap<>();
                observation.put("kind",reference.path("kind").asText()); observation.put("term",term);
                observation.put("esCount",esIds.size()); observation.put("pgCount",ids.size());
                observation.put("intersection",overlap.size()); observation.put("top20Intersection",topOverlap.size());
                observation.put("esTop20",esIds.stream().limit(20).toList()); observation.put("pgTop20",ids.stream().limit(20).toList());
                observation.put("pgCountAndPageMs",elapsed); observation.put("esServerMs",reference.path("server_ms").asInt());
                observation.put("esOnlySamples",samples(esIds,ids,rowsById)); observation.put("pgOnlySamples",samples(ids,esIds,rowsById));
                observations.add(observation);
                System.out.printf("%s %s ES=%d PG=%d common=%d top20Common=%d PGms=%s%n",
                        reference.path("kind").asText(),term,esIds.size(),ids.size(),overlap.size(),topOverlap.size(),elapsed);
            }
            Map<String,Object> report=new LinkedHashMap<>(Map.of(
                    "cohort", input.path("cohort").asText(), "rows", rowsById.size(), "queries", observations,
                    "limits", List.of("No labelled relevance ground truth", "ES IDF uses full index",
                            "ES measured on VM, PG on isolated 1 CPU/512MiB container; not a speedup comparison",
                            "Different counts are observations, not automatically correctness failures")));
            if (args.length==3) {
                try (KoreanPostgresqlSearchEvaluation candidate=new KoreanPostgresqlSearchEvaluation(jdbc)) {
                    report.put("noriCandidate",candidate.compare(input));
                }
                try (com.pawbridge.animalservice.search.KoreanSearchAnalyzer analyzer=new com.pawbridge.animalservice.search.KoreanSearchAnalyzer()) {
                    com.pawbridge.animalservice.search.PostgresqlSearchProjector projector=new com.pawbridge.animalservice.search.PostgresqlSearchProjector(source,analyzer);
                    long start=System.nanoTime();
                    for(int attempt=0;attempt<21 && projector.refreshShelters(100)>0;attempt++) { }
                    for(int attempt=0;attempt<21 && projector.refreshAnimals(100)>0;attempt++) { }
                    long refreshMillis=(System.nanoTime()-start)/1_000_000;
                    PostgresqlAnimalQueryService runtime=new PostgresqlAnimalQueryService(source,null,new AnimalMapper(),java.util.Optional.of(analyzer));
                    List<Map<String,Object>> actual=new ArrayList<>();
                    for(JsonNode reference:input.path("queries")) {
                        String term=reference.path("term").asText();
                        AnimalSearchRequest request=new AnimalSearchRequest();
                        if(reference.path("kind").asText().equals("breed")) request.setBreed(term);else request.setKeyword(term);
                        List<Long> times=new ArrayList<>();
                        Page<AnimalResponse> page=null;
                        for(int attempt=0;attempt<4;attempt++) {
                            long queryStart=System.nanoTime();
                            page=runtime.searchAnimals(request,PageRequest.of(0,20,Sort.by("relevance")));
                            times.add((System.nanoTime()-queryStart)/1_000_000);
                        }
                        List<Long> ids=new ArrayList<>();
                        for(int number=0;number<(page.getTotalElements()+99)/100;number++)
                            ids.addAll(runtime.searchAnimals(request,PageRequest.of(number,100,Sort.by("relevance"))).stream().map(AnimalResponse::getId).toList());
                        actual.add(Map.of("term",term,"kind",reference.path("kind").asText(),"count",page.getTotalElements(),"ids",ids,"readinessCountAndPageMs",times));
                    }
                    report.put("runtimeProjection",Map.of("analyzerVersion",com.pawbridge.animalservice.search.KoreanSearchAnalyzer.VERSION,
                            "refreshMs",refreshMillis,"queries",actual,"productionChanged",false));
                }
            }
            json.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[1]).toFile(),report);
        }
    }
    private static String value(JsonNode row, String field) {
        return row.path(field).isMissingNode() || row.path(field).isNull() ? null : row.path(field).asText();
    }
    private static List<Map<String,Object>> samples(List<Long> selected,List<Long> excluded,Map<Long,JsonNode> rows) {
        return selected.stream().filter(id -> !excluded.contains(id)).limit(3).map(id -> {
            JsonNode row = rows.get(id);
            Map<String,Object> result = new LinkedHashMap<>(); result.put("id",id);
            for (String field : List.of("breed","color","special_mark")) result.put(field,value(row,field));
            return result;
        }).toList();
    }
}
