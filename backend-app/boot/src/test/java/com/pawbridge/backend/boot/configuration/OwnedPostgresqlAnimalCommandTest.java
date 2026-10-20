package com.pawbridge.backend.boot.configuration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.animal.api.AnimalCommandActors;
import com.pawbridge.backend.animal.internal.command.infrastructure.service.AnimalCommandService;
import com.pawbridge.backend.animal.internal.discovery.infrastructure.KoreanSearchAnalyzer;
import com.pawbridge.backend.boot.security.PostTokenAuthenticator;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.persistence.EntityManagerFactory;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.SecretKey;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual MVC/security/JPA contracts against one explicitly owned reduced fixture, never production. */
@Tag("owned-postgresql")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(30)
class OwnedPostgresqlAnimalCommandTest {
    private static final String ROOT = "/api/v1/animals";
    private static final long ADMIN = 910001, SHELTER = 910002, WRONG_SHELTER = 910003;
    private static final long FOREIGN_ANIMAL = 990020;
    private static final SecretKey KEY = Keys.hmacShaKeyFor(
            "owned-p8-public-synthetic-signing-key-20261020-only".getBytes(StandardCharsets.UTF_8));
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final Map<String, String> TABLE_KEYS = Map.of(
            "pawbridge_user.users", "user_id", "pawbridge_user.contact_deletions", "user_id",
            "pawbridge_animal.shelters", "id", "pawbridge_animal.animals", "id",
            "pawbridge_animal.outbox_events", "id", "pawbridge_animal.chatbot_sessions", "id",
            "pawbridge_animal.animal_search_documents", "animal_id",
            "pawbridge_animal.shelter_search_documents", "shelter_id");
    private final Set<Long> ownedIds = new LinkedHashSet<>();
    private final Set<String> ownedKeys = new LinkedHashSet<>();
    private HikariDataSource pool;
    private DriverManagerDataSource probe;
    private JdbcTemplate jdbc;
    private Map<String, Fingerprint> baseline;

    @BeforeAll
    void openOnlyTheVerifiedOwnedWriter() {
        observed("owned writer identity", () -> {
            Settings settings = Settings.load();
            probe = new DriverManagerDataSource(settings.jdbcUrl(), settings.username(), settings.password());
            Properties network = new Properties();
            network.setProperty("connectTimeout", "3"); network.setProperty("socketTimeout", "5");
            probe.setConnectionProperties(network);
            verifyServer(new JdbcTemplate(probe), settings);
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(settings.jdbcUrl()); config.setUsername(settings.username()); config.setPassword(settings.password());
            config.setPoolName("owned-animal-command-writer"); config.setMaximumPoolSize(1); config.setMinimumIdle(0);
            config.setConnectionTimeout(2000); config.setValidationTimeout(1000); config.setInitializationFailTimeout(-1);
            config.setReadOnly(false); config.addDataSourceProperty("connectTimeout", "3"); config.addDataSourceProperty("socketTimeout", "5");
            pool = new HikariDataSource(config); jdbc = new JdbcTemplate(pool); jdbc.setQueryTimeout(5);
            check(jdbc.queryForObject("SELECT count(*) FROM pawbridge_animal.animals WHERE special_mark LIKE 'OWNED-P8-%'", Long.class) == 0,
                    "A previous trial's owned rows must not be absorbed into a new baseline");
            baseline = fingerprint();
            check(count("SELECT count(*) FROM pawbridge_animal.animals WHERE id=?", FOREIGN_ANIMAL) == 1,
                    "The protected foreign Animal must exist before the test");
            check(count("SELECT count(*) FROM pawbridge_animal.chatbot_sessions WHERE animal_id=?", FOREIGN_ANIMAL) == 1,
                    "The source FK fixture must exist before the test");
            released();
        });
    }

    @BeforeEach
    void requireTheUnchangedForeignBaseline() {
        observed("unchanged fixture baseline", () -> {
            check(ownedIds.isEmpty() && ownedKeys.isEmpty() && fingerprint().equals(baseline),
                    "Every case must start at the owned fixture baseline");
            released();
        });
    }

    @AfterEach
    void removeOnlyResponseOwnedRowsAndPreserveEveryForeignRow() {
        observed("owned row cleanup", this::cleanupOwnedRows);
    }

    @AfterAll
    void closeOnlyTheOwnedWriterPool() {
        if (pool != null) { pool.close(); check(pool.isClosed(), "The owned writer pool must close"); }
    }

    @Test
    void givenCurrentDatabaseAdministratorAndStaleUserJwt_whenCreating_thenPersistManualAuditSearchAndDistinctOutboxIds() {
        http((mvc, context) -> {
            JsonNode created = create(mvc, ADMIN, null, "owned-care-b", "synthetic breed");
            long id = created.path("id").asLong();
            check("MANUAL".equals(created.path("apiSource").asText())
                    && created.path("apmsNoticeNo").asText().matches("MAN-\\d{6}-\\d{6}"),
                    "The original default MANUAL source and generated notice format must survive");
            Map<String, Object> animal = animal(id);
            check(animal.get("created_at") != null && animal.get("updated_at") != null,
                    "Actual JPA auditing must populate both timestamps on create");
            check(((Number) animal.get("shelter_id")).longValue() == 990011L,
                    "The current database administrator may create for another shelter despite a stale User JWT role");
            projection(id, 1);
            assertEvents(id, List.of("animal.created"));
            released();
        });
    }

    @Test
    void givenCurrentOwningShelterAndApmsAnimal_whenUsingAllFourJsonCommands_thenKeepOriginalEventsAuditAndSearchRevision() {
        http((mvc, context) -> {
            String notice = "OWNED-P8-FOUR-COMMANDS";
            Map<String, Object> body = body(notice, "owned-care-a", "synthetic breed"); body.put("apiSource", "APMS_ANIMAL");
            JsonNode created = send(mvc, "POST", ROOT, token(SHELTER), JSON.writeValueAsString(body), 201);
            long id = remember(created); Map<String, Object> before = animal(id);
            check("APMS_ANIMAL".equals(created.path("apiSource").asText()), "An explicit source must not be rewritten to MANUAL");
            send(mvc, "PATCH", ROOT + "/" + id + "/status", token(SHELTER), "{\"newStatus\":\"ADOPTED\"}", 200);
            Map<String, Object> status = animal(id);
            check("ADOPTED".equals(status.get("status")) && before.get("created_at").equals(status.get("created_at")),
                    "A status change must preserve the original creation audit field");
            check(((java.sql.Timestamp) status.get("updated_at")).after((java.sql.Timestamp) before.get("updated_at")),
                    "The real update must advance the JPA audit timestamp");
            projection(id, 1);
            send(mvc, "PATCH", ROOT + "/" + id + "/description", token(SHELTER), "{\"description\":\"새로운 보호 설명\"}", 200);
            check("새로운 보호 설명".equals(animal(id).get("description")), "The original description command must persist");
            projection(id, 2);
            assertEvents(id, List.of("animal.created", "animal.status.changed", "animal.updated"));
            var events = events(id);
            JsonNode changed = JSON.readTree((String) events.get(1).get("payload"));
            check("PROTECT".equals(changed.path("oldStatus").asText()) && "ADOPTED".equals(changed.path("newStatus").asText())
                    && notice.equals(changed.path("animalName").asText()), "The original status payload fields must remain exact");
            JsonNode described = JSON.readTree((String) events.get(2).get("payload"));
            check("새로운 보호 설명".equals(described.path("updatedFields").path("description").asText()),
                    "The original updatedFields event shape must survive");
            send(mvc, "DELETE", ROOT + "/" + id, token(SHELTER), null, 204);
            check(count("SELECT count(*) FROM pawbridge_animal.animals WHERE id=?", id) == 0
                    && count("SELECT count(*) FROM pawbridge_animal.animal_search_documents WHERE animal_id=?", id) == 0,
                    "Delete and the original search-document FK cascade must commit together");
            check(events(id).size() == 3, "The original delete command must not invent another Outbox event");
            released();
        });
    }

    @ParameterizedTest
    @ValueSource(longs = {910004, 910005, 910006, 910099})
    void givenCurrentUserPendingMissingOrUnboundShelterProfile_whenJwtClaimsAdmin_thenRejectWithoutAnyChange(long member) {
        http((mvc, context) -> {
            Map<String, Fingerprint> before = fingerprint();
            send(mvc, "POST", ROOT, token(member, "ROLE_ADMIN"), JSON.writeValueAsString(body("OWNED-P8-DENIED", "owned-care-a", "breed")), 403);
            check(fingerprint().equals(before), "Current User role, deletion and shelter state must override privileged JWT and X-User claims");
            released();
        });
    }

    @Test
    void givenAnotherCurrentShelter_whenCreatingUpdatingOrDeletingTheOwnersAnimal_thenRejectAllFourCommandsWithoutMutation() {
        http((mvc, context) -> {
            long id = create(mvc, SHELTER, "OWNED-P8-OWNER", "owned-care-a", "breed").path("id").asLong();
            Map<String, Fingerprint> before = fingerprint();
            send(mvc, "POST", ROOT, token(WRONG_SHELTER), JSON.writeValueAsString(body("OWNED-P8-WRONG", "owned-care-a", "breed")), 403);
            send(mvc, "PATCH", ROOT + "/" + id + "/status", token(WRONG_SHELTER), "{\"newStatus\":\"ADOPTED\"}", 403);
            send(mvc, "PATCH", ROOT + "/" + id + "/description", token(WRONG_SHELTER), "{\"description\":\"forged change\"}", 403);
            send(mvc, "DELETE", ROOT + "/" + id, token(WRONG_SHELTER), null, 403);
            check(fingerprint().equals(before), "The actual current shelter owner predicate must protect every command");
            released();
        });
    }

    @Test
    void givenMissingOrInvalidJwtForgedIdentityOrInvalidDto_whenCallingJsonRoutes_thenAuthenticateBeforeBindingAndKeepNeighborClosed() {
        http((mvc, context) -> {
            Map<String, Fingerprint> before = fingerprint();
            send(mvc, "POST", ROOT, null, "not-json", 401);
            send(mvc, "POST", ROOT, "Bearer invalid.synthetic.token", "{}", 401);
            send(mvc, "POST", ROOT, token(ADMIN), "{}", 400);
            send(mvc, "PATCH", ROOT + "/" + FOREIGN_ANIMAL + "/description", token(ADMIN), "{\"description\":\" \"}", 400);
            send(mvc, "POST", ROOT + "/extra", token(ADMIN), "{}", 403);
            check(fingerprint().equals(before), "Authentication, validation and closed neighboring routes must perform no mutation");
            released();
        });
    }

    @Test
    void givenTheOriginalRollbackCharsetConstraint_whenCreatingFourByteText_thenReturn400AndRollBackEveryWrite() {
        http((mvc, context) -> {
            Map<String, Fingerprint> before = fingerprint();
            Map<String, Object> body = body("OWNED-P8-CHARSET", "owned-care-a", "breed"); body.put("description", "synthetic \uD83D\uDC36");
            JsonNode result = send(mvc, "POST", ROOT, token(SHELTER), JSON.writeValueAsString(body), 400);
            check(result.path("code").asInt() == 400 && fingerprint().equals(before),
                    "The exact original V7 charset refusal must remain 400 and atomic");
            released();
        });
    }

    @Test
    void givenTheOriginalChatbotForeignKey_whenAnAdministratorDeletesItsAnimal_thenReturn500AndPreserveTheForeignBaseline() {
        http((mvc, context) -> {
            Map<String, Fingerprint> before = fingerprint();
            send(mvc, "DELETE", ROOT + "/" + FOREIGN_ANIMAL, token(ADMIN), null, 500);
            check(fingerprint().equals(before), "The source FK delete failure must roll back Animal and search changes");
            released();
        });
    }

    @Test
    void givenTheExplicitSyntheticOutboxFailure_whenCreatingThenRetrying_thenRollBackAnimalSearchAndEventBeforeNormalRecovery() {
        http((mvc, context) -> {
            Map<String, Fingerprint> before = fingerprint();
            send(mvc, "POST", ROOT, token(SHELTER),
                    JSON.writeValueAsString(body("OWNED-P8-RETRY", "owned-care-a", "OWNED-P8-FAIL-OUTBOX")), 500);
            check(fingerprint().equals(before), "An Outbox failure after Animal/search writes must roll all three back");
            long id = create(mvc, SHELTER, "OWNED-P8-RETRY", "owned-care-a", "normal retry breed").path("id").asLong();
            check(count("SELECT count(*) FROM pawbridge_animal.animals WHERE apms_notice_no=?", "OWNED-P8-RETRY") == 1,
                    "A normal retry after rollback must create exactly one owned Animal");
            projection(id, 1); assertEvents(id, List.of("animal.created")); released();
        });
    }

    @Test
    void givenTheActualServiceWriterTemplate_whenLockingTheCurrentUser_thenUseWritableReadCommittedAndOnePoolConnectionUntilCompletion() {
        http((mvc, context) -> {
            var writes = (TransactionTemplate) ReflectionTestUtils.getField(context.getBean(AnimalCommandService.class), "writes");
            check(writes != null, "The actual service writer template must be selected");
            writes.executeWithoutResult(status -> {
                check("off".equals(jdbc.queryForObject("SHOW transaction_read_only", String.class))
                        && "read committed".equals(jdbc.queryForObject("SHOW transaction_isolation", String.class)),
                        "PostgreSQL must report the actual writable READ COMMITTED service transaction");
                check(TransactionSynchronizationManager.hasResource(pool) && pool.getHikariPoolMXBean().getActiveConnections() == 1,
                        "JpaTM must bind the sole pooled writer connection for JDBC");
                var actor = context.getBean(AnimalCommandActors.class).find(SHELTER);
                check(actor.active() && !actor.deleted() && "ROLE_SHELTER".equals(actor.role()),
                        "The real User JDBC lookup must participate in the Animal writer transaction");
                // A separate physical connection is a bounded lock probe, not a second application pool.
                try (Connection connection = probe.getConnection();
                        var statement = connection.prepareStatement("SELECT user_id FROM pawbridge_user.users WHERE user_id=? FOR UPDATE NOWAIT")) {
                    statement.setLong(1, SHELTER); statement.setQueryTimeout(3);
                    try (var ignored = statement.executeQuery()) { throw new AssertionError("The current User row lock must exclude another connection"); }
                } catch (SQLException failure) {
                    check("55P03".equals(failure.getSQLState()), "The independent connection must observe the actual User row lock");
                }
            });
            released();
        });
    }

    @Test
    void givenACommittedCreateAndAResponseAssertionFailure_whenCleaning_thenFindThePreRegisteredExactKeyWithoutAResponseId() {
        http((mvc, context) -> {
            Map<String, Object> body = body(null, "owned-care-a", "response failure cleanup breed");
            String key = (String) body.get("specialMark");
            AssertionError refusal = null;
            try {
                // Deliberately reject the real 201 before any response JSON or ID is consumed.
                send(mvc, "POST", ROOT, token(SHELTER), JSON.writeValueAsString(body), 500);
            } catch (AssertionError expected) { refusal = expected; }
            check(refusal != null && ownedIds.isEmpty()
                    && count("SELECT count(*) FROM pawbridge_animal.animals WHERE special_mark=?", key) == 1,
                    "The negative observation must commit one owned row without recording a response identity");
            cleanupOwnedRows();
            check(fingerprint().equals(baseline), "Exact-key cleanup must restore the foreign baseline after response failure");
        });
    }

    private void cleanupOwnedRows() {
        released();
        Set<Long> discovered = new LinkedHashSet<>(ownedIds);
        boolean unique = true;
        for (String key : ownedKeys) {
            List<Long> rows = jdbc.query("SELECT id FROM pawbridge_animal.animals WHERE special_mark=?",
                    (row, index) -> row.getLong(1), key);
            unique &= rows.size() <= 1;
            discovered.addAll(rows);
        }
        for (long id : discovered)
            check(id > 0 && id != FOREIGN_ANIMAL, "Cleanup cannot select an invalid or foreign fixture Animal");
        for (long id : discovered) {
            jdbc.update("DELETE FROM pawbridge_animal.outbox_events WHERE aggregate_type='Animal' AND aggregate_id=?", Long.toString(id));
            jdbc.update("DELETE FROM pawbridge_animal.animals WHERE id=?", id);
        }
        ownedIds.clear(); ownedKeys.clear();
        // Identity sequence consumption on failed/rolled-back inserts is deliberately outside this fingerprint.
        check(fingerprint().equals(baseline), "All eight tables, including foreign seeds, must return to their baseline");
        check(unique, "Each generated request key must identify at most one Animal");
        released();
    }

    private void http(HttpCheck action) {
        observed("actual secured MVC/JPA contract", () -> {
            AtomicReference<EntityManagerFactory> factory = new AtomicReference<>();
            new WebApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class,
                            JacksonAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class,
                            ValidationAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
                            WebMvcAutoConfiguration.class, SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class))
                    .withUserConfiguration(AnimalCommandConfiguration.class, ReadSecurityConfiguration.class)
                    .withPropertyValues("pawbridge.animal-commands.enabled=true", "spring.sql.init.mode=never", "spring.jmx.enabled=false")
                    .withBean("writeDataSource", DataSource.class, () -> pool, definition -> definition.setDestroyMethodName(""))
                    .withBean("writeJdbc", JdbcTemplate.class, () -> jdbc)
                    .withBean(PostTokenAuthenticator.class, () -> new PostTokenAuthenticator(KEY))
                    .run(context -> {
                        check(context.getStartupFailure() == null, "The real Animal command and security configuration must start");
                        factory.set(context.getBean("animalCommandEntityManagerFactory", EntityManagerFactory.class));
                        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
                                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
                        try { action.run(mvc, context); }
                        catch (AssertionError failure) { throw failure; }
                        catch (Exception failure) {
                            throw new AssertionError("Owned Animal HTTP observation failed: " + failure.getClass().getName());
                        }
                    });
            check(factory.get() != null && !factory.get().isOpen(), "Each owned application context must close its Animal factory");
            check(!pool.isClosed(), "Closing a context must not close the externally owned fixture pool");
            released();
        });
    }

    private JsonNode create(MockMvc mvc, long member, String notice, String care, String breed) throws Exception {
        JsonNode created = send(mvc, "POST", ROOT, token(member), JSON.writeValueAsString(body(notice, care, breed)), 201);
        remember(created); return created;
    }
    private long remember(JsonNode created) {
        long id = created.path("id").asLong();
        check(id > 0 && id != FOREIGN_ANIMAL, "A successful create must return a new owned Animal identity");
        String key = created.path("specialMark").asText();
        check(ownedKeys.contains(key), "A response must preserve its pre-dispatch owned request key");
        List<Long> observed = jdbc.query("SELECT id FROM pawbridge_animal.animals WHERE special_mark=?",
                (row, index) -> row.getLong(1), key);
        check(observed.size() == 1 && observed.getFirst() == id,
                "The response identity must match the exact pre-dispatch request key in the database");
        ownedIds.add(id); return id;
    }
    private Map<String, Object> body(String notice, String care, String breed) {
        Map<String, Object> body = new LinkedHashMap<>();
        // Create DTO/mapper expose specialMark but not the entity's unique apmsDesertionNo.
        // This UUID key is observed by exact equality; database uniqueness is not assumed.
        String key = "OWNED-P8-" + UUID.randomUUID();
        ownedKeys.add(key); body.put("specialMark", key);
        body.put("careRegNo", care); if (notice != null) body.put("apmsNoticeNo", notice);
        body.put("noticeStartDate", "2026-10-20"); body.put("noticeEndDate", "2026-10-30");
        body.put("species", "DOG"); body.put("gender", "MALE"); body.put("neuterStatus", "NO");
        body.put("status", "PROTECT"); body.put("breed", breed); body.put("description", "initial synthetic description");
        return body;
    }
    private static String token(long member) { return token(member, "ROLE_USER"); }
    private static String token(long member, String staleRole) {
        return "Bearer " + Jwts.builder().claim("userId", member).claim("role", staleRole)
                .expiration(new Date(System.currentTimeMillis() + 300_000)).signWith(KEY).compact();
    }
    private static JsonNode send(MockMvc mvc, String method, String path, String authorization, String body, int expected) throws Exception {
        var request = MockMvcRequestBuilders.request(HttpMethod.valueOf(method), path).servletPath(path)
                .contentType(MediaType.APPLICATION_JSON).characterEncoding(StandardCharsets.UTF_8)
                .header("X-User-Id", Long.toString(ADMIN)).header("X-User-Role", "ROLE_ADMIN");
        if (authorization != null) request.header("Authorization", authorization);
        if (body != null) request.content(body);
        var response = mvc.perform(request).andReturn().getResponse();
        check(response.getStatus() == expected,
                "Unexpected Animal HTTP status: expected=" + expected + ", actual=" + response.getStatus());
        check(response.getHeader("Set-Cookie") == null, "The stateless contract must not issue a session cookie");
        String content = response.getContentAsString(StandardCharsets.UTF_8);
        return content.isEmpty() ? JSON.getNodeFactory().nullNode() : JSON.readTree(content);
    }
    private Map<String, Object> animal(long id) { return jdbc.queryForMap("SELECT * FROM pawbridge_animal.animals WHERE id=?", id); }
    private void projection(long id, long revision) {
        Map<String, Object> value = jdbc.queryForMap("SELECT a.search_revision,a.search_dirty,d.source_revision,d.analyzer_version,d.tokens::text AS tokens "
                + "FROM pawbridge_animal.animals a JOIN pawbridge_animal.animal_search_documents d ON d.animal_id=a.id WHERE a.id=?", id);
        check(((Number) value.get("search_revision")).longValue() == revision
                && ((Number) value.get("source_revision")).longValue() == revision
                && Boolean.FALSE.equals(value.get("search_dirty")) && KoreanSearchAnalyzer.VERSION.equals(value.get("analyzer_version"))
                && !((String) value.get("tokens")).isBlank(), "The persisted Nori projection and source revision must commit together");
    }
    private List<Map<String, Object>> events(long id) {
        return jdbc.queryForList("SELECT * FROM pawbridge_animal.outbox_events WHERE aggregate_type='Animal' AND aggregate_id=? ORDER BY id", Long.toString(id));
    }
    private void assertEvents(long id, List<String> topics) throws Exception {
        List<Map<String, Object>> events = events(id);
        check(events.size() == topics.size(), "Only the original command events must be recorded");
        Set<String> uuids = new LinkedHashSet<>();
        for (int index = 0; index < events.size(); index++) {
            Map<String, Object> event = events.get(index); JsonNode payload = JSON.readTree((String) event.get("payload"));
            String outboxId = (String) event.get("event_id"), payloadId = payload.path("eventId").asText();
            UUID.fromString(outboxId); UUID.fromString(payloadId);
            check(!outboxId.equals(payloadId) && uuids.add(outboxId) && uuids.add(payloadId), "Payload and Outbox UUIDs must remain distinct");
            check(topics.get(index).equals(event.get("topic")) && event.get("event_type").equals(payload.path("eventType").asText())
                    && payload.path("animalId").asLong() == id && event.get("created_at") != null
                    && !payload.path("timestamp").asText().isBlank(), "The original route, event envelope and real audit fields must persist");
        }
    }
    private long count(String sql, Object value) { return jdbc.queryForObject(sql, Long.class, value); }
    private Map<String, Fingerprint> fingerprint() {
        Map<String, Fingerprint> result = new LinkedHashMap<>();
        TABLE_KEYS.forEach((table, key) -> {
            Map<String, Object> value = jdbc.queryForMap("SELECT count(*) AS n,md5(COALESCE(string_agg(row_to_json(owned_row)::text,',' ORDER BY "
                    + key + "),'')) AS digest FROM " + table + " owned_row");
            result.put(table, new Fingerprint(((Number) value.get("n")).longValue(), (String) value.get("digest")));
        });
        return result;
    }
    private void released() {
        check(!TransactionSynchronizationManager.isActualTransactionActive() && !TransactionSynchronizationManager.hasResource(pool),
                "No Animal transaction or User JDBC binding may escape the operation");
        check(pool.getHikariPoolMXBean().getActiveConnections() == 0 && pool.getHikariPoolMXBean().getThreadsAwaitingConnection() == 0,
                "The single writer pool must return its connection after success or rollback");
    }
    private static void verifyServer(JdbcTemplate jdbc, Settings settings) {
        jdbc.setQueryTimeout(5);
        Map<String, Object> identity = jdbc.queryForMap("SELECT current_database() AS db,current_user AS actor,session_user AS session,"
                + "current_setting('server_version_num') AS version,(SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname=current_database()) AS owner");
        check(settings.database().equals(identity.get("db")) && settings.username().equals(identity.get("actor"))
                && settings.username().equals(identity.get("session")) && "170011".equals(identity.get("version"))
                && !settings.username().equals(identity.get("owner")), "Owned database, PostgreSQL version and separate writer identity must match");
        List<String> markers = jdbc.query("SELECT marker FROM public.pawbridge_owned_fixture", (row, index) -> row.getString(1));
        check(markers.size() == 1 && settings.marker().equals(markers.getFirst()), "The exact isolated fixture marker must match");
        check(Boolean.TRUE.equals(jdbc.queryForObject("SELECT NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole "
                + "AND NOT pg_has_role(current_user,(SELECT datdba FROM pg_database WHERE datname=current_database()),'MEMBER') "
                + "FROM pg_roles WHERE rolname=current_user", Boolean.class)), "The writer must not inherit administrative or owner privileges");
        for (String schema : List.of("public", "pawbridge_animal", "pawbridge_user"))
            check(Boolean.FALSE.equals(jdbc.queryForObject("SELECT has_schema_privilege(current_user,?,'CREATE')", Boolean.class, schema)),
                    "The selected writer must not create schema objects");
        for (String table : TABLE_KEYS.keySet())
            check(Boolean.TRUE.equals(jdbc.queryForObject("SELECT has_table_privilege(current_user,?,'SELECT') "
                    + "AND NOT has_table_privilege(current_user,?,'TRUNCATE,REFERENCES,TRIGGER')", Boolean.class, table, table)),
                    "Only the selected nonadministrative fixture table grants may be used");
        check(Boolean.TRUE.equals(jdbc.queryForObject("SELECT has_column_privilege(current_user,'pawbridge_user.users','user_id','UPDATE') "
                + "AND NOT has_column_privilege(current_user,'pawbridge_user.users','role','UPDATE') "
                + "AND NOT has_column_privilege(current_user,'pawbridge_user.users','care_reg_no','UPDATE') "
                + "AND NOT has_table_privilege(current_user,'pawbridge_user.users','INSERT,DELETE,UPDATE') "
                + "AND NOT has_table_privilege(current_user,'pawbridge_user.contact_deletions','INSERT,UPDATE,DELETE')", Boolean.class)),
                "The actor writer may lock User rows but must not edit roles, shelter identity or deletion state");
    }
    private static void observed(String phase, Checked action) {
        try { action.run(); }
        catch (AssertionError failure) { throw failure; }
        catch (Throwable failure) {
            throw new AssertionError("Owned Animal command observation failed: " + phase + "; type=" + failure.getClass().getName());
        }
    }
    private static void check(boolean condition, String fixedMessage) { if (!condition) throw new AssertionError(fixedMessage); }
    private interface Checked { void run() throws Exception; }
    private interface HttpCheck { void run(MockMvc mvc, ApplicationContext context) throws Exception; }
    private record Fingerprint(long count, String digest) { }
    private record Settings(String jdbcUrl, String database, String username, String password, String marker) {
        static Settings load() throws Exception {
            String path = System.getenv("P8_PG_SETTINGS");
            check(path != null && !path.isBlank(), "P8_PG_SETTINGS is mandatory; owned tests must not skip");
            Path file = Path.of(path);
            check(file.isAbsolute() && file.normalize().equals(file) && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(file), "Settings must be an absolute regular non-symlink file");
            check(Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS).equals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
                    && ((Number) Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).intValue() == 1,
                    "Settings must have exactly 0600 permissions and one hard link");
            check(System.getProperty("user.name").equals(Files.getOwner(file).getName())
                    && Files.getOwner(file).equals(Files.getOwner(file.getParent())), "Settings and parent must belong to this process owner");
            for (Path parent = file.getParent(); parent != null; parent = parent.getParent())
                check(!Files.isSymbolicLink(parent), "Settings ancestors must not be symbolic links");
            Set<PosixFilePermission> parentPermissions = Files.getPosixFilePermissions(file.getParent(), LinkOption.NOFOLLOW_LINKS);
            check(!parentPermissions.contains(PosixFilePermission.GROUP_WRITE) && !parentPermissions.contains(PosixFilePermission.OTHERS_WRITE),
                    "Settings parent must reject writes from other users");
            Properties values = new Properties();
            try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { values.load(input); }
            String database = required(values, "database"), url = required(values, "jdbcUrl");
            check(database.matches("pb_animal_command_owned_[0-9a-f]{12}")
                    && url.equals("jdbc:postgresql://127.0.0.1:52620/" + database), "Only the explicitly owned fixed loopback database is allowed");
            String username = required(values, "username"), marker = required(values, "marker");
            check(username.equals("pb_animal_command_writer") && marker.matches("[0-9a-f]{32}"), "Writer and fixture marker must be explicit");
            return new Settings(url, database, username, required(values, "password"), marker);
        }
        private static String required(Properties values, String key) {
            String value = values.getProperty(key); check(value != null && !value.isBlank(), "Every owned setting is required"); return value;
        }
        @Override public String toString() { return "OwnedAnimalCommandSettings[redacted]"; }
    }
}
