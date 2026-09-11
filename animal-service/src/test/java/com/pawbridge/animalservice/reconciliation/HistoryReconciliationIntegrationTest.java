package com.pawbridge.animalservice.reconciliation;

import com.pawbridge.animalservice.entity.*;
import com.pawbridge.animalservice.batch.processor.AnimalItemProcessor;
import com.pawbridge.animalservice.enums.AnimalStatus;
import com.pawbridge.animalservice.repository.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import javax.sql.DataSource;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Opt-in: real disposable MySQL + ES only. No production endpoints or APMS calls. */
@DataJpaTest
@ActiveProfiles("ci")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(HistoryReconciliationIntegrationTest.Auditing.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIfEnvironmentVariable(named = "HISTORY_REHEARSAL", matches = "true")
class HistoryReconciliationIntegrationTest {
    @Autowired DataSource dataSource;
    @Autowired AnimalRepository animals;
    @Autowired ShelterRepository shelters;
    @TempDir Path directory;
    final String es = "http://127.0.0.1:23200";
    final HistoryHttp http = new HistoryHttp();
    Shelter shelter;
    @BeforeEach void isolatedFixtures() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:mysql://127.0.0.1:23316/pawbridge_ci");
        }
        var populator = new ResourceDatabasePopulator(new ClassPathResource("org/springframework/batch/core/schema-mysql.sql"));
        populator.setContinueOnError(true); populator.execute(dataSource);
        animals.deleteAllInBatch(); shelters.deleteAllInBatch();
        shelter = shelters.saveAndFlush(Shelter.builder().careRegNo("history-shelter").name("Rehearsal shelter").build());
        http.request("DELETE", es + "/animals_history_rehearsal", null, null, true);
        http.request("PUT", es + "/animals_history_rehearsal", Map.of("aliases", Map.of("animals", Map.of())), null, false);
    }
    HistoryStore store() throws Exception { return new HistoryStore(dataSource.getConnection()); }
    HistorySearch search() { return new HistorySearch(es, null); }
    HistoryPlan plan(HistoryStore store, String... ids) throws Exception {
        var items = Arrays.stream(ids).map(HistoryPlanTest::animal).toList();
        return store.plan(HistoryPlanTest.MONTH, new HistoryCollector.Snapshot(items.size(), items, List.of()), search().identity());
    }
    void apply(HistoryPlan plan, HistoryStore store, HistorySearch search) throws Exception {
        try (var journal = new HistoryJournal(directory.resolve("journal.jsonl"), "reviewed-plan")) {
            HistoryReconciliation.apply(plan, "reviewed-plan", store, search, journal);
        }
    }
    Animal existing(String id) {
        var source = HistoryPlanTest.animal(id);
        source.setProcessState("보호중"); source.setHappenDt("20260101"); source.setUpdTm("2026-01-03 00:00:00.0");
        var animal = AnimalItemProcessor.createNewAnimal(source, shelter);
        animal.updateDescription("user text"); animal.incrementFavoriteCount();
        return animals.saveAndFlush(animal);
    }
    @Test void defaultCliOnlyPlansFullHistoricalPayloadAndNeverWrites() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var requests = new ArrayList<String>();
        server.createContext("/abandonmentPublic_v2", exchange -> {
            requests.add(exchange.getRequestURI().getQuery());
            byte[] body = HistoryPlan.JSON.writeValueAsBytes(Map.of("response", Map.of("header", Map.of("resultCode", "00"),
                    "body", Map.of("pageNo", 1, "totalCount", 1, "items", Map.of("item", List.of(HistoryPlanTest.animal("new")))))));
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            var environment = Map.of("HISTORY_JDBC_URL", "jdbc:mysql://127.0.0.1:23316/pawbridge_ci", "HISTORY_DB_USER", "pawbridge_ci",
                    "HISTORY_DB_PASSWORD", "ci_placeholder", "HISTORY_ES_URL", es,
                    "APMS_API_BASE_URL", "http://127.0.0.1:" + server.getAddress().getPort(), "APMS_API_SERVICE_KEY", "fixture-key");
            Path file = directory.resolve("plan.json");
            HistoryReconciliationCli.run(new String[]{"--month", "2026-01", "--plan", file.toString()}, environment);
            assertThat(animals.count()).isZero();
            assertThat(requests).singleElement().asString().contains("bgnde=20260101", "endde=20260131").doesNotContain("bgupd", "enupd", "state=");
            assertThat(HistoryPlan.JSON.readValue(Files.readAllBytes(file), HistoryPlan.class).entries()).hasSize(1);
            assertThat(http.request("GET", es + "/animals/_count", null, null, false).body().path("count").asInt()).isZero();
        } finally { server.stop(0); }
    }
    @Test void unchangedExistingInvalidNoticeDoesNotBlockPlanningValidMissingAnimal() throws Exception {
        var old = existing("existing");
        var existingSource = HistoryPlanTest.animal("existing");
        existingSource.setProcessState("보호중"); existingSource.setHappenDt("20260101");
        existingSource.setUpdTm("2026-01-03 00:00:00.0");
        existingSource.setNoticeSdt("20260131"); existingSource.setNoticeEdt("20260130");
        var missing = HistoryPlanTest.animal("missing");
        var snapshot = new HistoryCollector((month, page) ->
                new HistoryCollector.Page(page, 2, List.of(existingSource, missing))).collect(HistoryPlanTest.MONTH);
        try (var store = store()) {
            var plan = store.plan(HistoryPlanTest.MONTH, snapshot, search().identity());
            assertThat(plan.reported()).isEqualTo(2);
            assertThat(plan.observed()).isEqualTo(2);
            assertThat(plan.warnings()).isEmpty();
            assertThat(plan.entries()).singleElement().satisfies(entry -> {
                assertThat(entry.source().getDesertionNo()).isEqualTo("missing");
                assertThat(entry.before()).isNull();
            });
        }
        assertThat(animals.count()).isEqualTo(1);
        var retained = animals.findById(old.getId()).orElseThrow();
        assertThat(retained.getStatus()).isEqualTo(old.getStatus());
        assertThat(retained.getNoticeStartDate()).isEqualTo(old.getNoticeStartDate());
        assertThat(retained.getNoticeEndDate()).isEqualTo(old.getNoticeEndDate());
        assertThat(retained.getDescription()).isEqualTo("user text");
        assertThat(retained.getFavoriteCount()).isEqualTo(1);
        assertThat(http.request("GET", es + "/animals/_count", null, null, false).body().path("count").asInt()).isZero();
    }
    @Test void insertsMissingIdsWithExistingMapperAndRerunsWithoutDuplicatesOrWatermarkChanges() throws Exception {
        long jobsBefore = new JdbcTemplate(dataSource).queryForObject("SELECT COUNT(*) FROM BATCH_JOB_EXECUTION", Long.class);
        try (var store = store()) {
            var plan = plan(store, "new-one", "new-two");
            assertThat(animals.count()).isZero();
            Files.write(directory.resolve("saved.json"), HistoryPlan.JSON.writeValueAsBytes(plan));
            apply(plan, store, search());
            var first = animals.findByApmsDesertionNo("new-one").orElseThrow();
            assertThat(first.getBreed()).isEqualTo("믹스견"); assertThat(first.getBirthYear()).isEqualTo(2024);
            assertThat(first.getImageUrl()).isEqualTo(HistoryPlanTest.animal("new-one").getPopfile1());
            assertThat(first.getStatus()).isEqualTo(AnimalStatus.ADOPTED); assertThat(first.getFavoriteCount()).isZero();
            assertThat(first.getDescription()).isNull();
            assertThat(first.getApmsNoticeNo()).isEqualTo(animals.findByApmsDesertionNo("new-two").orElseThrow().getApmsNoticeNo());
        }
        // The approved plan is reused, including its original missing-before state.
        var approved = HistoryPlan.JSON.readValue(Files.readAllBytes(directory.resolve("saved.json")), HistoryPlan.class);
        var idsBefore = animals.findAll().stream().map(Animal::getId).sorted().toList();
        try (var store = store()) { apply(approved, store, search()); }
        assertThat(animals.findAll().stream().map(Animal::getId).sorted().toList()).isEqualTo(idsBefore);
        assertThat(new JdbcTemplate(dataSource).queryForObject("SELECT COUNT(*) FROM BATCH_JOB_EXECUTION", Long.class)).isEqualTo(jobsBefore);
    }
    @Test void correctsStateAndIntakeDatePreservingIdentityUserFieldsAndVector() throws Exception {
        var before = existing("old");
        before = animals.findById(before.getId()).orElseThrow();
        http.request("PUT", es + "/animals/_doc/" + before.getId(), Map.of("id", before.getId(), "apms_desertion_no", "old",
                "status", "PROTECT", "description", "ES user text", "favorite_count", 9, "image_vector", List.of(0.1, 0.2)), null, false);
        try (var store = store()) { apply(plan(store, "old"), store, search()); }
        var after = animals.findById(before.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(AnimalStatus.ADOPTED); assertThat(after.getHappenDate()).hasToString("2026-01-02");
        assertThat(after.getDescription()).isEqualTo("user text"); assertThat(after.getFavoriteCount()).isEqualTo(1);
        assertThat(after.getUpdatedAt()).isEqualTo(before.getUpdatedAt());
        var source = http.request("GET", es + "/animals/_doc/" + before.getId(), null, null, false).body().path("_source");
        assertThat(source.path("status").asText()).isEqualTo("ADOPTED");
        assertThat(source.path("description").asText()).isEqualTo("ES user text");
        assertThat(source.path("favorite_count").asInt()).isEqualTo(9);
        assertThat(source.path("image_vector")).isEqualTo(HistoryPlan.JSON.valueToTree(List.of(0.1, 0.2)));
    }
    @Test void resumesAfterSearchFailureWithCommittedDbAndNoDuplicateInsert() throws Exception {
        HistoryPlan plan;
        try (var store = store()) {
            plan = plan(store, "new");
            var failing = new HistorySearch(es, null) {
                @Override public void sync(String target, long id, Map<String, Object> current) { throw new IllegalStateException("injected failure"); }
            };
            assertThatThrownBy(() -> apply(plan, store, failing)).hasMessage("injected failure");
        }
        long id = animals.findByApmsDesertionNo("new").orElseThrow().getId();
        assertThat(Files.readString(directory.resolve("journal.jsonl"))).contains("DB_COMMITTED", "INCOMPLETE").doesNotContain("\"phase\":\"COMPLETED\"");
        try (var store = store()) { apply(plan, store, search()); }
        assertThat(animals.count()).isEqualTo(1);
        assertThat(animals.findByApmsDesertionNo("new").orElseThrow().getId()).isEqualTo(id);
        assertThat(http.request("GET", es + "/animals/_doc/" + id, null, null, false).body().path("_source").path("status").asText()).isEqualTo("ADOPTED");
    }
    @Test void refusesStalePlanAfterUserChangesState() throws Exception {
        var before = existing("old");
        try (var store = store()) {
            var plan = plan(store, "old");
            new JdbcTemplate(dataSource).update("UPDATE animals SET status='RETURNED' WHERE id=?", before.getId());
            assertThatThrownBy(() -> apply(plan, store, search())).hasMessageContaining("changed since");
        }
        assertThat(animals.findById(before.getId()).orElseThrow().getStatus()).isEqualTo(AnimalStatus.RETURNED);
    }
    @Test void resumeRetainsNewerBatchVersionAndSynchronizesCurrentDbInsteadOfOldPlan() throws Exception {
        var before = existing("old");
        try (var store = store()) {
            var plan = plan(store, "old");
            new JdbcTemplate(dataSource).update("UPDATE animals SET status='RETURNED', apms_process_state='종료(반환)', apms_updated_at='2026-09-11 00:00:00' WHERE id=?", before.getId());
            apply(plan, store, search());
        }
        assertThat(animals.findById(before.getId()).orElseThrow().getStatus()).isEqualTo(AnimalStatus.RETURNED);
        assertThat(http.request("GET", es + "/animals/_doc/" + before.getId(), null, null, false).body().path("_source").path("status").asText()).isEqualTo("RETURNED");
        assertThat(Files.readString(directory.resolve("journal.jsonl"))).contains("NEWER_DB_RETAINED");
    }
    @Test void concurrentInsertIsRetainedAndUnreturnedDbRecordIsNeverDeleted() throws Exception {
        try (var store = store()) {
            var plan = plan(store, "new");
            var concurrent = existing("new"); var unreturned = existing("unreturned");
            apply(plan, store, search());
            assertThat(animals.findById(concurrent.getId()).orElseThrow().getStatus()).isEqualTo(AnimalStatus.PROTECT);
            assertThat(animals.findById(unreturned.getId())).isPresent();
            assertThat(animals.count()).isEqualTo(2);
        }
    }
    @Test void usesRegularBatchNamedLockAndReleasesItOnClose() throws Exception {
        try (var first = store()) {
            first.acquire();
            try (var other = store()) { assertThatThrownBy(other::acquire).hasMessageContaining("lock unavailable"); }
        }
        try (var next = store()) { next.acquire(); }
    }
    @Test void wrongEnvironmentOrReplacedAliasPreventsDbWrites() throws Exception {
        try (var store = store()) {
            var plan = plan(store, "new");
            http.request("POST", es + "/_aliases", Map.of("actions", List.of(Map.of("remove", Map.of("index", "animals_history_rehearsal", "alias", "animals")))), null, false);
            assertThatThrownBy(() -> apply(plan, store, search())).isInstanceOf(IllegalStateException.class);
            assertThat(animals.count()).isZero();
        }
    }
    @Test
    @EnabledIfEnvironmentVariable(named = "HISTORY_PAYLOAD_DIR", matches = ".+")
    void capturedCandidatePayloadsPersistAndIndexWithRealConstraints() throws Exception {
        Path payload = Path.of(System.getenv("HISTORY_PAYLOAD_DIR"));
        var sourceByMonth = new TreeMap<Integer, List<com.pawbridge.animalservice.dto.apms.ApmsAnimal>>();
        var allSources = new HashMap<String, com.pawbridge.animalservice.dto.apms.ApmsAnimal>();
        for (int month = 1; month <= 9; month++) {
            var items = new ArrayList<com.pawbridge.animalservice.dto.apms.ApmsAnimal>();
            for (var node : HistoryPlan.JSON.readTree(Files.readAllBytes(payload.resolve(String.format("candidates-%02d.json", month))))) {
                var source = HistoryPlan.JSON.treeToValue(node, com.pawbridge.animalservice.dto.apms.ApmsAnimal.class);
                HistoryPlan.validateSource(source, java.time.YearMonth.of(2026, month));
                items.add(source); allSources.put(source.getDesertionNo(), source);
            }
            sourceByMonth.put(month, items);
        }
        var jdbc = new JdbcTemplate(dataSource);
        shelters.deleteAllInBatch();
        for (var node : HistoryPlan.JSON.readTree(Files.readAllBytes(payload.resolve("shelters.json")))) {
            jdbc.update("INSERT INTO shelters (id,care_reg_no,name,phone,address,created_at,updated_at) VALUES (?,?,?,?,?,NOW(6),NOW(6))",
                    node.path("id").asLong(), node.path("care_reg_no").asText(), node.path("name").asText(),
                    node.path("phone").asText(null), node.path("address").asText(null));
        }
        int beforeCount = 0;
        for (var node : HistoryPlan.JSON.readTree(Files.readAllBytes(payload.resolve("db-after.json")))) {
            var source = allSources.get(node.path("apms_desertion_no").asText());
            if (source == null) continue;
            long shelterId = jdbc.queryForObject("SELECT id FROM shelters WHERE care_reg_no=?", Long.class, source.getCareRegNo());
            var values = HistoryStore.insertFields(AnimalItemProcessor.createNewAnimal(source, Shelter.builder().id(shelterId).build()));
            long id = node.path("id").asLong();
            values.put("id", id); values.put("status", node.path("status").asText());
            values.put("apms_process_state", node.path("apms_process_state").asText(null));
            values.put("happen_date", node.path("happen_date").asText(null));
            values.put("apms_updated_at", node.path("apms_updated_at").asText(null));
            values.put("description", "rehearsal user text"); values.put("favorite_count", 7);
            jdbc.update("INSERT INTO animals (" + String.join(",", values.keySet()) + ") VALUES ("
                    + String.join(",", Collections.nCopies(values.size(), "?")) + ")", values.values().toArray());
            http.request("PUT", es + "/animals/_doc/" + id, Map.of("id", id, "apms_desertion_no", source.getDesertionNo(),
                    "status", node.path("status").asText(), "image_vector", List.of(0.125, -0.25),
                    "apms_process_state", node.path("apms_process_state").asText(),
                    "happen_date", node.path("happen_date").asText(),
                    "apms_updated_at", node.path("apms_updated_at").asText().replace(' ', 'T'),
                    "description", "rehearsal ES user text", "favorite_count", 11), null, false);
            beforeCount++;
        }
        int targets = 0;
        long started = System.nanoTime();
        for (var month : sourceByMonth.entrySet()) {
            if (month.getValue().isEmpty()) continue;
            HistoryPlan plan;
            try (var store = store()) {
                plan = store.plan(java.time.YearMonth.of(2026, month.getKey()),
                        new HistoryCollector.Snapshot(month.getValue().size(), month.getValue(), List.of("Scoped candidate payload rehearsal, not full-month coverage")), search().identity());
                targets += plan.entries().size();
                String hash = HistoryJournal.hash(HistoryPlan.JSON.writeValueAsBytes(plan));
                try (var journal = new HistoryJournal(directory.resolve("payload-" + month.getKey() + ".jsonl"), hash)) {
                    HistoryReconciliation.apply(plan, hash, store, search(), journal);
                }
            }
        }
        assertThat(animals.count()).isEqualTo(allSources.size());
        assertThat(http.request("GET", es + "/animals/_count", null, null, false).body().path("count").asInt()).isEqualTo(allSources.size());
        for (var node : HistoryPlan.JSON.readTree(Files.readAllBytes(payload.resolve("db-after.json")))) {
            if (!allSources.containsKey(node.path("apms_desertion_no").asText())) continue;
            long id = node.path("id").asLong();
            var animal = animals.findById(id).orElseThrow();
            assertThat(animal.getDescription()).isEqualTo("rehearsal user text"); assertThat(animal.getFavoriteCount()).isEqualTo(7);
            var document = http.request("GET", es + "/animals/_doc/" + id, null, null, false).body().path("_source");
            assertThat(document.path("image_vector")).isEqualTo(HistoryPlan.JSON.valueToTree(List.of(0.125, -0.25)));
            assertThat(document.path("description").asText()).isEqualTo("rehearsal ES user text");
            assertThat(document.path("favorite_count").asInt()).isEqualTo(11);
        }
        // Compare every result by stable DB ID, not only aggregate counts.
        var hits = http.request("POST", es + "/animals/_search", Map.of("size", allSources.size(),
                "_source", List.of("id", "apms_desertion_no", "status", "apms_process_state", "happen_date", "apms_updated_at")), null, false)
                .body().path("hits").path("hits");
        var indexed = new HashMap<Long, com.fasterxml.jackson.databind.JsonNode>();
        for (var hit : hits) indexed.put(hit.path("_source").path("id").asLong(), hit.path("_source"));
        assertThat(indexed).hasSize(allSources.size());
        for (var animal : animals.findAll()) {
            var document = indexed.get(animal.getId());
            assertThat(document.path("apms_desertion_no").asText()).isEqualTo(animal.getApmsDesertionNo());
            assertThat(document.path("status").asText()).isEqualTo(animal.getStatus().name());
            assertThat(document.path("apms_process_state").asText()).isEqualTo(animal.getApmsProcessState());
            assertThat(document.path("happen_date").asText()).isEqualTo(animal.getHappenDate().toString());
            assertThat(java.time.LocalDateTime.parse(document.path("apms_updated_at").asText())).isEqualTo(animal.getApmsUpdatedAt());
        }
        var result = Map.of("payloadCandidates", allSources.size(), "existingFixtures", beforeCount, "targets", targets,
                "dbCount", animals.count(), "seconds", (System.nanoTime() - started) / 1_000_000_000.0,
                "mode", "ISOLATED_MYSQL_ES_WITH_CAPTURED_APMS_PAYLOAD");
        Files.write(Path.of("build/history-payload-rehearsal.json"), HistoryPlan.JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(result));
    }

    @TestConfiguration(proxyBeanMethods = false) @EnableJpaAuditing static class Auditing { }
}
