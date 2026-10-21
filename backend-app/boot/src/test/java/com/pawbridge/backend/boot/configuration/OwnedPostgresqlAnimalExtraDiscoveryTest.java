package com.pawbridge.backend.boot.configuration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.pawbridge.backend.boot.security.PostTokenAuthenticator;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.jsonwebtoken.security.Keys;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.slf4j.LoggerFactory;
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
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.data.web.SpringDataWebAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** P10 reader reuse with at most six registered synthetic rows; no original seed or privilege changes. */
@Tag("owned-postgresql")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(45)
class OwnedPostgresqlAnimalExtraDiscoveryTest {
    private static final String DB = "pb_animal_command_owned_02c82195e3c7";
    private static final String PREFIX = "OWNED-EXTRA-DISCOVERY-";
    private static final long SHELTER = 990010;
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final Map<String,String> TABLES = Map.of(
            "pawbridge_animal.animals", "id", "pawbridge_animal.shelters", "id",
            "pawbridge_animal.outbox_events", "id", "pawbridge_animal.chatbot_sessions", "id",
            "pawbridge_animal.animal_search_documents", "animal_id", "pawbridge_animal.shelter_search_documents", "shelter_id",
            "pawbridge_user.users", "user_id", "pawbridge_user.contact_deletions", "user_id",
            "public.pawbridge_owned_fixture", "marker");
    private static final List<String> SEQUENCES = List.of("pawbridge_animal.animals_id_seq", "pawbridge_animal.outbox_events_id_seq");
    private HikariDataSource pool;
    private ObservedSource applicationSource, fixtureSource;
    private JdbcTemplate control;
    private TransactionTemplate preparation;
    private Pair settings;
    private Baseline original;
    private List<Seed> seeds;
    private final Map<Long,Seed> registered = new LinkedHashMap<>();
    private LocalDate day;
    private String shelterName;
    private final Map<Logger,Level> previousLogLevels = new LinkedHashMap<>();

    @BeforeAll void requireExactP10PairAndOriginalBaselineBeforeRegisteringAnySyntheticRows() {
        observed("P10 paired identity", () -> {
            for (String name : List.of("org.springframework.jdbc", "com.zaxxer.hikari", "org.springframework.web", "org.springframework.test.web.servlet", "org.springframework.security")) {
                Logger logger = (Logger) LoggerFactory.getLogger(name); previousLogLevels.put(logger, logger.getLevel()); logger.setLevel(Level.OFF);
            }
            settings = Pair.load();
            var fixtureDriver = new DriverManagerDataSource(settings.writer().url(), settings.writer().username(), settings.writer().password());
            Properties timeout = new Properties(); timeout.setProperty("connectTimeout", "3"); timeout.setProperty("socketTimeout", "5");
            fixtureDriver.setConnectionProperties(timeout); fixtureSource = new ObservedSource(fixtureDriver, false);
            control = new JdbcTemplate(fixtureSource); control.setQueryTimeout(5);
            verifyIdentity(control, settings.writer(), false);
            List<String> fixtureTables = control.query("SELECT schemaname||'.'||tablename FROM pg_tables WHERE schemaname IN ('public','pawbridge_animal','pawbridge_user') ORDER BY schemaname,tablename", (r,n) -> r.getString(1));
            check(fixtureTables.stream().sorted().toList().equals(TABLES.keySet().stream().sorted().toList()), "Only the original eight application tables and marker are accepted before preparation");
            for (String table : TABLES.keySet()) check(Boolean.TRUE.equals(control.queryForObject("SELECT has_table_privilege(current_user,?,'SELECT')", Boolean.class, table)), "The fixture role must already be able to observe the complete original baseline");
            check(Boolean.TRUE.equals(control.queryForObject("SELECT has_table_privilege(current_user,'pawbridge_animal.animals','INSERT') AND has_table_privilege(current_user,'pawbridge_animal.animals','DELETE')", Boolean.class)), "Existing fixture Animal insert/delete privileges are required without any grant");
            for (String sequence : SEQUENCES) check(Boolean.TRUE.equals(control.queryForObject("SELECT has_sequence_privilege(current_user,?,'SELECT')", Boolean.class, sequence)), "Only already permitted sequence state may be observed");
            preparation = new TransactionTemplate(new JdbcTransactionManager(fixtureSource)); preparation.setTimeout(5);
            HikariConfig config = new HikariConfig(); config.setJdbcUrl(settings.reader().url()); config.setUsername(settings.reader().username()); config.setPassword(settings.reader().password());
            config.setPoolName("owned-p10-extra-reader"); config.setMaximumPoolSize(1); config.setMinimumIdle(0); config.setReadOnly(true);
            config.setConnectionTimeout(2000); config.setValidationTimeout(1000); config.setInitializationFailTimeout(-1);
            config.addDataSourceProperty("connectTimeout", "3"); config.addDataSourceProperty("socketTimeout", "5"); pool = new HikariDataSource(config);
            var readerProbe = new JdbcTemplate(pool); readerProbe.setQueryTimeout(5); verifyIdentity(readerProbe, settings.reader(), true);
            applicationSource = new ObservedSource(pool, true);
            check(control.queryForObject("SELECT count(*) FROM pawbridge_animal.animals WHERE id IN (990110,990111,990112,990113,990114,990115)", Long.class) == 6L, "All six original P10 filter rows must remain present");
            check(control.queryForObject("SELECT count(*) FROM pawbridge_animal.animals WHERE id=990999", Long.class) == 0L, "The original P10 missing candidate must remain absent");
            noResidue(); shelterName = control.queryForObject("SELECT name FROM pawbridge_animal.shelters WHERE id=?", String.class, SHELTER);
            check(shelterName != null, "Only the existing P10 shelter may be referenced");
            original = baseline(false); long start = 8_000_000_000L + Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 100_000_000L) * 10;
            List<Seed> selected = new ArrayList<>();
            for (int index = 0; index < 6; index++) {
                String nonce = UUID.randomUUID().toString().replace("-", "");
                long id = start + (index == 0 ? 2 : index == 1 ? 1 : index + 1);
                selected.add(new Seed(id, "EXTRA-" + nonce, "OWNED-EXTRA-" + nonce, PREFIX + nonce, index));
            }
            seeds = List.copyOf(selected); idle();
        });
    }
    @BeforeEach void requireRestoredOriginalStateAndAStableJvmDate() {
        observed("extra before", () -> {
            idle(); check(registered.isEmpty(), "No registration may survive the preceding method");
            check(baseline(false).equals(original), "All eight original tables, marker and permitted sequence state must remain unchanged");
            noResidue(); day = LocalDate.now();
            check(control.queryForObject("SELECT count(*) FROM pawbridge_animal.animals WHERE status='PROTECT' AND notice_end_date BETWEEN ? AND ?", Long.class, day, day.plusDays(3)) == 0L,
                    "The unmodified original fixture must have no current expiring rows");
            applicationSource.reset();
        });
    }
    @AfterEach void removeOnlyRegisteredExactTriplesAndRestoreTheCompleteOriginalBaseline() {
        observed("extra exact cleanup", () -> {
            idle(); remove(new ArrayList<>(registered.values()));
            check(registered.isEmpty() && baseline(false).equals(original), "Only registered additions may disappear and every original table/sequence must return to baseline");
            noResidue(); idle();
        });
    }
    @AfterAll void closeOnlyTheIdleReaderPool() {
        try { if (pool != null) { idle(); pool.close(); check(pool.isClosed(), "The test-owned reader pool must close"); } }
        finally { previousLogLevels.forEach(Logger::setLevel); }
    }

    @Test void givenOneFullyPopulatedOwnedAnimal__whenLookingUpItsApmsNumber__thenReturnAll28FieldsAndShelterJoinOr404UsingOnlyTheReader() {
        observed("APMS actual reader", () -> {
            Seed own = seeds.getFirst(); insert(List.of(own)); Baseline withOwn = baseline(false);
            http(mvc -> {
                JsonNode actual = get(mvc, "/api/v1/animals/apms/" + own.apms(), Map.of(), 200, 1);
                JsonNode expected = JSON.readTree(JSON.writeValueAsBytes(details(own)));
                check(actual.size() == 28 && actual.equals(expected), "Actual APMS lookup must preserve all28 non-null original detail fields and the existing shelter join value");
                get(mvc, "/api/v1/animals/apms/MISSING-" + UUID.randomUUID().toString().replace("-", ""), Map.of(), 404, 1);
            });
            check(baseline(false).equals(withOwn), "The APMS success and404 paths must not write even the added row"); originalsUnchanged();
        });
    }

    @Test void givenSixRegisteredBoundaryRows__whenExpiringSoonIsPagedAndTheOwnedEligibleSubsetIsRemoved__thenKeepInclusiveFixedSortPage7AndTrueEmptyWithoutOriginalWrites() {
        observed("expiring actual reader", () -> {
            insert(seeds); Baseline withOwn = baseline(false);
            List<Long> expected = List.of(seeds.get(1).id(), seeds.get(0).id(), seeds.get(2).id());
            http(mvc -> {
                page(get(mvc, "/api/v1/animals/expiring-soon", Map.of(), 200, 2), expected, 0, 20, 3, 1, true, true);
                page(get(mvc, "/api/v1/animals/expiring-soon", Map.of("sort", "id,desc"), 200, 2), expected, 0, 20, 3, 1, true, true);
                page(get(mvc, "/api/v1/animals/expiring-soon", Map.of("sort", "unknown,desc"), 200, 2), expected, 0, 20, 3, 1, true, true);
                for (int number = 0; number < 3; number++)
                    page(get(mvc, "/api/v1/animals/expiring-soon", Map.of("page", Integer.toString(number), "size", "1"), 200, 2),
                            List.of(expected.get(number)), number, 1, 3, 3, number == 0, number == 2);
                page(get(mvc, "/api/v1/animals/expiring-soon", Map.of("page", "3", "size", "1"), 200, 1), List.of(), 3, 1, 3, 3, false, true);
            });
            check(baseline(false).equals(withOwn), "Every positive/offset query must leave all original and registered rows unchanged"); originalsUnchanged();
            remove(seeds.subList(0, 3)); Baseline withExcludedOnly = baseline(false);
            http(mvc -> page(get(mvc, "/api/v1/animals/expiring-soon", Map.of(), 200, 1), List.of(), 0, 20, 0, 0, true, true));
            check(baseline(false).equals(withExcludedOnly), "The truly empty path must not change the remaining excluded or original rows"); originalsUnchanged();
        });
    }

    private void http(HttpCheck action) throws Exception {
        new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class,
                JacksonAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class, ValidationAutoConfiguration.class,
                SpringDataWebAutoConfiguration.class, DispatcherServletAutoConfiguration.class, WebMvcAutoConfiguration.class,
                SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class))
                .withUserConfiguration(AnimalDiscoveryConfiguration.class, ReadSecurityConfiguration.class)
                .withBean("dataSource", DataSource.class, () -> applicationSource)
                .withBean("transactionManager", JdbcTransactionManager.class, () -> new JdbcTransactionManager(applicationSource))
                .withBean(PostTokenAuthenticator.class, () -> new PostTokenAuthenticator(Keys.hmacShaKeyFor(
                        "owned-extra-discovery-public-synthetic-token-key-only-20261021".getBytes(StandardCharsets.UTF_8))))
                .withPropertyValues("pawbridge.animal-discovery.enabled=true", "pawbridge.animal-query.backend=postgresql", "spring.sql.init.mode=never", "spring.jmx.enabled=false")
                .run(context -> {
                    check(context.getStartupFailure() == null, "The actual discovery controller and Root security chain must start");
                    MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(SecurityMockMvcConfigurers.springSecurity()).build();
                    try { action.run(mvc); } catch (AssertionError failure) { throw failure; } catch (Throwable failure) { throw new AssertionError("Owned extra discovery HTTP observation failed"); }
                });
        check(!pool.isClosed(), "Context closure must not close the external reader pool"); idle();
    }
    private JsonNode get(MockMvc mvc, String route, Map<String,String> params, int expected, int expectedSelects) throws Exception {
        idle(); sameDay(); applicationSource.reset(); int controlBefore = fixtureSource.borrowed.get();
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.get(route); params.forEach(request::param);
        var response = mvc.perform(request).andReturn().getResponse();
        check(response.getStatus() == expected, "Unexpected owned extra discovery HTTP status");
        check(fixtureSource.borrowed.get() == controlBefore, "The application request must never borrow the fixture account");
        check(applicationSource.borrowed.get() == 1 && applicationSource.samples.size() == expectedSelects, "The actual reader must use one borrow and the expected business SELECT count");
        check(applicationSource.samples.stream().allMatch(s -> s.readOnly() && s.manualCommit() && s.bound()
                && s.actor().equals("pb_lost_search_reader") && s.isolation().equals("repeatable read") && s.timeout() > 0 && s.timeout() <= 5),
                "Actual PostgreSQL reader mode, actor, bounded statement timeout and binding must match the production RR contract");
        check(applicationSource.samples.stream().map(Sample::pid).distinct().count() == 1, "Page count/content must share the same physical snapshot connection");
        idle(); sameDay(); return JSON.readTree(response.getContentAsByteArray());
    }
    private static void page(JsonNode actual, List<Long> expected, int number, int size, long total, int totalPages, boolean first, boolean last) {
        Set<String> fields = new HashSet<>(); actual.fieldNames().forEachRemaining(fields::add);
        check(fields.equals(Set.of("content", "number", "size", "totalElements", "totalPages", "first", "last")), "The original seven-field Page wire contract must remain exact");
        List<Long> ids = new ArrayList<>(); actual.path("content").forEach(row -> ids.add(row.path("id").asLong()));
        check(ids.equals(expected) && actual.path("number").asInt() == number && actual.path("size").asInt() == size
                && actual.path("totalElements").asLong() == total && actual.path("totalPages").asInt() == totalPages
                && actual.path("first").asBoolean() == first && actual.path("last").asBoolean() == last,
                "Inclusive boundaries, ID ties, fixed sorting, page offset and empty metadata must retain their actual contracts");
    }

    private void insert(List<Seed> selected) {
        idle(); sameDay(); check(registered.isEmpty() && selected.size() <= 6, "Preparation may register at most six fresh rows after baseline restoration");
        for (Seed seed : selected) {
            check(control.queryForObject("SELECT count(*) FROM pawbridge_animal.animals WHERE id=? OR apms_desertion_no=? OR special_mark=?", Long.class,
                    seed.id(), seed.apms(), seed.mark()) == 0L, "Every registered ID, APMS number and UUID marker must be absent before insertion");
            registered.put(seed.id(), seed);
        }
        preparation.executeWithoutResult(status -> {
            for (Seed seed : selected) {
                Map<String,Object> value = details(seed);
                int changed = control.update("INSERT INTO pawbridge_animal.animals(id,created_at,updated_at,api_source,apms_desertion_no,apms_notice_no,apms_process_state,apms_updated_at,birth_year,breed,color,description,favorite_count,gender,happen_date,happen_place,image_url,image_url2,neuter_status,notice_end_date,notice_start_date,special_mark,species,status,weight,shelter_id,search_revision,search_dirty) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,1,false)",
                        seed.id(), value.get("createdAt"), value.get("updatedAt"), "APMS_ANIMAL", seed.apms(), seed.notice(), value.get("apmsProcessState"), value.get("apmsUpdatedAt"),
                        2001, value.get("breed"), value.get("color"), value.get("description"), 3, "MALE", value.get("happenDate"), value.get("happenPlace"),
                        value.get("imageUrl"), value.get("imageUrl2"), "YES", value.get("noticeEndDate"), value.get("noticeStartDate"), seed.mark(), "DOG", value.get("status"), "7.5", SHELTER);
                check(changed == 1, "Only the exact registered Animal insert may commit");
            }
        });
        idle(); originalsUnchanged();
    }
    private void remove(List<Seed> selected) {
        idle(); if (selected.isEmpty()) return; List<Seed> completed = new ArrayList<>();
        preparation.executeWithoutResult(status -> {
            for (Seed seed : selected) {
                check(seed.equals(registered.get(seed.id())), "Cleanup may only inspect the current run's preregistered identity");
                List<Map<String,Object>> found = control.queryForList("SELECT id,apms_desertion_no,special_mark FROM pawbridge_animal.animals WHERE id=? OR apms_desertion_no=? OR special_mark=?",
                        seed.id(), seed.apms(), seed.mark());
                check(found.size() <= 1, "A registered identity must not resolve to ambiguous or foreign rows");
                if (!found.isEmpty()) {
                    Map<String,Object> row = found.getFirst();
                    check(((Number) row.get("id")).longValue() == seed.id() && seed.apms().equals(row.get("apms_desertion_no")) && seed.mark().equals(row.get("special_mark")),
                            "ID, APMS number and UUID marker must all match before cleanup");
                    check(control.queryForObject("SELECT count(*) FROM pawbridge_animal.chatbot_sessions WHERE animal_id=?", Long.class, seed.id()) == 0L
                            && control.queryForObject("SELECT count(*) FROM pawbridge_animal.animal_search_documents WHERE animal_id=?", Long.class, seed.id()) == 0L
                            && control.queryForObject("SELECT count(*) FROM pawbridge_animal.outbox_events WHERE aggregate_type='Animal' AND aggregate_id=?", Long.class, Long.toString(seed.id())) == 0L,
                            "Unexpected dependent rows forbid cleanup instead of cascading or deleting them");
                    check(control.update("DELETE FROM pawbridge_animal.animals WHERE id=? AND apms_desertion_no=? AND special_mark=?", seed.id(), seed.apms(), seed.mark()) == 1,
                            "Cleanup must affect exactly its three-condition registered Animal");
                }
                completed.add(seed);
            }
        });
        // Keep registrations if commit acknowledgement is uncertain; do not silently adopt or wipe rows.
        completed.forEach(seed -> registered.remove(seed.id())); idle();
    }
    private Map<String,Object> details(Seed seed) {
        LocalDate end = switch (seed.index()) { case 2 -> day.plusDays(3); case 3 -> day.minusDays(1); case 4 -> day.plusDays(4); default -> day; };
        Map<String,Object> value = new LinkedHashMap<>();
        value.put("id", seed.id()); value.put("apmsNoticeNo", seed.notice()); value.put("species", "DOG"); value.put("breed", "Owned breed");
        value.put("gender", "MALE"); value.put("neuterStatus", "YES"); value.put("birthYear", 2001); value.put("age", day.getYear() - 2001);
        value.put("weight", "7.5"); value.put("color", "brown"); value.put("specialMark", seed.mark()); value.put("status", seed.index() == 5 ? "NOTICE" : "PROTECT");
        value.put("noticeStartDate", day.minusDays(10)); value.put("noticeEndDate", end); value.put("happenPlace", "Owned place"); value.put("happenDate", day.minusDays(2));
        value.put("imageUrl", "https://fixture.invalid/extra/" + seed.apms() + "/one.jpg"); value.put("imageUrl2", "https://fixture.invalid/extra/" + seed.apms() + "/two.jpg");
        value.put("description", "Owned extra detail"); value.put("favoriteCount", 3); value.put("shelterId", SHELTER); value.put("shelterName", shelterName);
        value.put("apmsDesertionNo", seed.apms()); value.put("apmsProcessState", "Owned state"); value.put("apmsUpdatedAt", LocalDateTime.of(day.minusDays(1), LocalTime.of(10, 11, 12)));
        value.put("apiSource", "APMS_ANIMAL"); value.put("createdAt", LocalDateTime.of(day.minusDays(10), LocalTime.of(1, 2, 3)));
        value.put("updatedAt", LocalDateTime.of(day.minusDays(1), LocalTime.of(4, 5, 6))); check(value.size() == 28, "The owned detail oracle must contain exactly28 fields"); return value;
    }
    private Baseline baseline(boolean excludeRegistered) {
        Map<String,String> tables = new LinkedHashMap<>();
        TABLES.forEach((table, key) -> {
            String where = ""; Object[] args = {};
            if (excludeRegistered && table.equals("pawbridge_animal.animals") && !registered.isEmpty()) {
                where = " WHERE id NOT IN (" + String.join(",", registered.keySet().stream().map(id -> "?").toList()) + ")"; args = registered.keySet().toArray();
            }
            tables.put(table, control.queryForObject("SELECT count(*)::text||':'||md5(COALESCE(string_agg(row_to_json(r)::text,',' ORDER BY " + key + "),'')) FROM " + table + " r" + where, String.class, args));
        });
        Map<String,SequenceState> sequences = new LinkedHashMap<>();
        for (String sequence : SEQUENCES) {
            Map<String,Object> row = control.queryForMap("SELECT last_value,is_called FROM " + sequence);
            sequences.put(sequence, new SequenceState(((Number) row.get("last_value")).longValue(), (Boolean) row.get("is_called")));
        }
        return new Baseline(tables, sequences);
    }
    private void originalsUnchanged() { check(baseline(true).equals(original), "Every original table row, marker and allowed sequence must remain unchanged while owned additions exist"); }
    private void noResidue() { check(control.queryForObject("SELECT count(*) FROM pawbridge_animal.animals WHERE special_mark LIKE ?", Long.class, PREFIX + "%") == 0L, "Prior experimental residue forbids a new run and must never be prefix-deleted"); }
    private void sameDay() { check(day.equals(LocalDate.now()), "A JVM date change requires failure and exact cleanup, never a hidden clock policy or reseed"); }
    private void idle() {
        check(!TransactionSynchronizationManager.isActualTransactionActive()
                && (applicationSource == null || !TransactionSynchronizationManager.hasResource(applicationSource))
                && (fixtureSource == null || !TransactionSynchronizationManager.hasResource(fixtureSource)), "No application or preparation transaction may escape its phase");
        if (applicationSource != null) applicationSource.requireReturned();
        if (fixtureSource != null) fixtureSource.requireReturned();
        if (pool != null) check(pool.getHikariPoolMXBean().getActiveConnections() == 0 && pool.getHikariPoolMXBean().getThreadsAwaitingConnection() == 0,
                "The single application reader must have no active or waiting borrower");
    }
    private static void verifyIdentity(JdbcTemplate jdbc, Credential credential, boolean reader) {
        Map<String,Object> id = jdbc.queryForMap("SELECT current_database() db,current_user actor,session_user session,current_setting('server_version_num') version,(SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname=current_database()) owner");
        check(DB.equals(id.get("db")) && credential.username().equals(id.get("actor")) && credential.username().equals(id.get("session"))
                && "170011".equals(id.get("version")) && !credential.username().equals(id.get("owner")), "Only the exact P10 database, actor and separate owner are permitted; P8 fallback is forbidden");
        List<String> marker = jdbc.query("SELECT marker FROM public.pawbridge_owned_fixture", (r, n) -> r.getString(1));
        check(marker.size() == 1 && credential.marker().equals(marker.getFirst()), "Both accounts must observe the exact original single marker");
        check(Boolean.TRUE.equals(jdbc.queryForObject("SELECT NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls AND NOT rolinherit AND NOT pg_has_role(current_user,(SELECT datdba FROM pg_database WHERE datname=current_database()),'MEMBER') FROM pg_roles WHERE rolname=current_user", Boolean.class)), "Neither account may gain owner or administrative inheritance");
        for (String schema : List.of("public", "pawbridge_animal", "pawbridge_user")) check(Boolean.FALSE.equals(jdbc.queryForObject("SELECT has_schema_privilege(current_user,?,'CREATE')", Boolean.class, schema)), "Existing accounts must not create schema objects");
        check(Boolean.FALSE.equals(jdbc.queryForObject("SELECT has_database_privilege(current_user,current_database(),'CREATE,TEMP')", Boolean.class)), "Neither existing account may create database or temporary objects");
        if (reader) {
            for (String table : List.of("pawbridge_animal.animals", "pawbridge_animal.shelters")) check(Boolean.TRUE.equals(jdbc.queryForObject("SELECT has_table_privilege(current_user,?,'SELECT') AND NOT has_table_privilege(current_user,?,'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') AND NOT has_any_column_privilege(current_user,?,'INSERT,UPDATE,REFERENCES')", Boolean.class, table, table, table)), "The actual application reader must remain SELECT-only");
            check(jdbc.queryForObject("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname !~ '^pg_' AND n.nspname<>'information_schema' AND c.relkind IN('r','p','v','m','f') AND (has_table_privilege(current_user,c.oid,'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') OR has_any_column_privilege(current_user,c.oid,'INSERT,UPDATE,REFERENCES') OR ((has_table_privilege(current_user,c.oid,'SELECT') OR has_any_column_privilege(current_user,c.oid,'SELECT')) AND (n.nspname||'.'||c.relname) NOT IN ('pawbridge_animal.animals','pawbridge_animal.shelters','public.pawbridge_owned_fixture')))", Long.class) == 0L, "The reader must retain no writes or unrelated reads");
            check(jdbc.queryForObject("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname !~ '^pg_' AND n.nspname<>'information_schema' AND c.relkind='S' AND has_sequence_privilege(current_user,c.oid,'USAGE,SELECT,UPDATE')", Long.class) == 0L, "The reader must retain no sequence privilege");
            check("on".equals(jdbc.queryForObject("SHOW transaction_read_only", String.class)), "The existing reader must continue to default to read-only");
        }
    }

    private static final class ObservedSource extends DelegatingDataSource {
        final AtomicInteger borrowed = new AtomicInteger(), returned = new AtomicInteger(), statements = new AtomicInteger(), closedStatements = new AtomicInteger(), results = new AtomicInteger(), closedResults = new AtomicInteger();
        final List<Sample> samples = new ArrayList<>(); private final boolean application;
        ObservedSource(DataSource target, boolean application) { super(target); this.application = application; }
        @Override public Connection getConnection() throws SQLException { return wrap(super.getConnection()); }
        @Override public Connection getConnection(String user, String password) throws SQLException { return wrap(super.getConnection(user, password)); }
        private Connection wrap(Connection connection) {
            borrowed.incrementAndGet(); AtomicBoolean closed = new AtomicBoolean();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                if (method.getName().equals("close")) { if (!closed.get()) { call(connection, method, args); closed.set(true); returned.incrementAndGet(); } return null; }
                Object value = call(connection, method, args);
                if (application && method.getName().equals("prepareStatement") && value instanceof PreparedStatement statement && args[0] instanceof String sql)
                    return statement(connection, statement, sql);
                return value;
            });
        }
        private PreparedStatement statement(Connection connection, PreparedStatement statement, String sql) {
            statements.incrementAndGet(); AtomicBoolean closed = new AtomicBoolean();
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> {
                if (method.getName().equals("close")) { if (!closed.get()) { call(statement, method, args); closed.set(true); closedStatements.incrementAndGet(); } return null; }
                if (method.getName().equals("executeQuery") && sql.contains("FROM pawbridge_animal.animals a JOIN pawbridge_animal.shelters s")) {
                    try (var inspection = connection.createStatement(); var row = inspection.executeQuery("SELECT current_user,current_setting('transaction_read_only'),current_setting('transaction_isolation'),pg_backend_pid()")) {
                        check(row.next(), "Actual reader mode must be observable on its borrowed connection");
                        samples.add(new Sample(row.getString(1), "on".equals(row.getString(2)), row.getString(3), row.getInt(4), statement.getQueryTimeout(),
                                !connection.getAutoCommit(), TransactionSynchronizationManager.isActualTransactionActive()
                                && TransactionSynchronizationManager.isCurrentTransactionReadOnly() && TransactionSynchronizationManager.hasResource(ObservedSource.this)));
                    }
                }
                Object value = call(statement, method, args); return value instanceof ResultSet result ? result(result) : value;
            });
        }
        private ResultSet result(ResultSet result) {
            results.incrementAndGet(); AtomicBoolean closed = new AtomicBoolean();
            return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class}, (proxy, method, args) -> {
                if (method.getName().equals("close")) { if (!closed.get()) { call(result, method, args); closed.set(true); closedResults.incrementAndGet(); } return null; }
                return call(result, method, args);
            });
        }
        void requireReturned() { check(borrowed.get() == returned.get() && statements.get() == closedStatements.get() && results.get() == closedResults.get(), "Every connection, prepared statement and result cursor must return after the selected phase"); }
        void reset() { requireReturned(); borrowed.set(0); returned.set(0); statements.set(0); closedStatements.set(0); results.set(0); closedResults.set(0); samples.clear(); }
    }
    private static Object call(Object target, Method method, Object[] args) throws Throwable { try { return method.invoke(target, args); } catch (InvocationTargetException failure) { throw failure.getCause(); } }
    private static void observed(String phase, Checked action) { try { action.run(); } catch (AssertionError failure) { throw failure; } catch (Throwable failure) { throw new AssertionError("Owned extra discovery failed: " + phase + "; type=" + failure.getClass().getName()); } }
    private static void check(boolean value, String fixed) { if (!value) throw new AssertionError(fixed); }
    private interface Checked { void run() throws Exception; }
    private interface HttpCheck { void run(MockMvc mvc) throws Exception; }
    private record Seed(long id, String apms, String notice, String mark, int index) { }
    private record SequenceState(long last, boolean called) { }
    private record Baseline(Map<String,String> tables, Map<String,SequenceState> sequences) { }
    private record Sample(String actor, boolean readOnly, String isolation, int pid, int timeout, boolean manualCommit, boolean bound) { }
    private record Credential(String url, String database, String username, String password, String marker) {
        @Override public String toString() { return "OwnedExtraCredential[redacted]"; }
    }
    private record Pair(Credential reader, Credential writer) {
        static Pair load() throws Exception {
            String value = System.getenv("P10_PG_SETTINGS"); check(value != null && !value.isBlank(), "P10_PG_SETTINGS is mandatory without skip or P8 fallback");
            Path readerPath = Path.of(value); Properties read = protectedProperties(readerPath, "lost-search.properties");
            Properties write = protectedProperties(readerPath.resolveSibling("lost-search-fixture.properties"), "lost-search-fixture.properties");
            check(read.stringPropertyNames().equals(Set.of("jdbcUrl", "database", "username", "password", "marker")), "The reader must retain its exact five settings");
            check(write.stringPropertyNames().equals(Set.of("jdbcUrl", "database", "username", "password", "marker", "readerUsername", "readerPassword")), "Only the existing seven fixture settings may be consumed");
            Credential reader = credential(read), writer = credential(write);
            check(reader.database().equals(DB) && reader.url().equals("jdbc:postgresql://127.0.0.1:52620/" + DB)
                    && reader.username().equals("pb_lost_search_reader") && writer.username().equals("pb_lost_search_fixture")
                    && writer.url().equals(reader.url()) && writer.database().equals(reader.database()) && writer.marker().equals(reader.marker())
                    && required(write, "readerUsername").equals(reader.username()) && required(write, "readerPassword").equals(reader.password())
                    && reader.marker().matches("[0-9a-f]{32}"), "Both protected settings must identify the same exact P10 target and existing reader credentials");
            return new Pair(reader, writer);
        }
        private static Credential credential(Properties value) { return new Credential(required(value, "jdbcUrl"), required(value, "database"), required(value, "username"), required(value, "password"), required(value, "marker")); }
        private static Properties protectedProperties(Path path, String name) throws Exception {
            check(path.isAbsolute() && path.normalize().equals(path) && path.getFileName().toString().equals(name) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path), "Only the exact absolute regular non-link P10 settings pair is accepted");
            check(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).equals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
                    && ((Number) Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).intValue() == 1, "Both settings require0600 and one hard link");
            check(System.getProperty("user.name").equals(Files.getOwner(path).getName()) && Files.getOwner(path).equals(Files.getOwner(path.getParent())), "Both settings and parent must retain the current owner");
            for (Path parent = path.getParent(); parent != null; parent = parent.getParent()) check(!Files.isSymbolicLink(parent), "Settings ancestors must not be symbolic links");
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path.getParent());
            check(!permissions.contains(PosixFilePermission.GROUP_WRITE) && !permissions.contains(PosixFilePermission.OTHERS_WRITE), "The existing P10 settings parent must reject other writers");
            Properties result = new Properties(); try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) { result.load(input); } return result;
        }
        private static String required(Properties values, String key) { String value = values.getProperty(key); check(value != null && !value.isBlank(), "Every approved setting is mandatory"); return value; }
        @Override public String toString() { return "OwnedExtraSettingsPair[redacted]"; }
    }
}
