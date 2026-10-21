package com.pawbridge.backend.boot.configuration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.animal.internal.gallery.infrastructure.LostGalleryFeed;
import com.pawbridge.backend.animal.internal.gallery.infrastructure.LostGallerySnapshots;
import com.pawbridge.backend.animal.internal.gallery.web.LostGalleryFeedController;
import com.pawbridge.backend.animal.internal.gallery.web.LostGalleryPagesController;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual SQL and physical reader boundaries on the new SELECT-only P16 fixture; no DML or object transfer. */
@Tag("owned-postgresql")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(45)
class OwnedPostgresqlAnimalGalleryFeedTest {
    private static final String ROOT = "/internal/animals/lost-gallery";
    private static final String KEY = "owned-p16-synthetic-internal-key";
    private static final String REQUEST = "c".repeat(32);
    private static final String A = "a".repeat(64), B = "b".repeat(64);
    private static final List<Long> IDS = List.of(996101L, 996102L);
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final Map<String,String> TABLES = Map.of(
            "pawbridge_animal.animals", "id", "pawbridge_animal.apms_photo_archive", "animal_id,slot",
            "public.pb_gallery_feed_fixture", "marker");
    private Settings settings;
    private HikariDataSource pool;
    private ReaderSource reader;
    private JdbcTemplate jdbc;
    private TransactionTemplate reads, streams;
    private JdbcTemplate probe;
    private Map<String,Fingerprint> baseline;
    private final List<Sample> observations = new ArrayList<>();

    @BeforeAll void requireNewOwnedContainerReaderAndTwoImmutableTablesBeforeApplicationBorrow() {
        observed("P16 isolated identity", () -> {
            settings = Settings.load();
            var direct = new DriverManagerDataSource(settings.url(), settings.username(), settings.password());
            Properties timeouts = new Properties(); timeouts.setProperty("connectTimeout", "3"); timeouts.setProperty("socketTimeout", "5");
            direct.setConnectionProperties(timeouts); probe = new JdbcTemplate(direct); probe.setQueryTimeout(5);
            verifyServer();
            HikariConfig config = new HikariConfig(); config.setJdbcUrl(settings.url());
            config.setUsername(settings.username()); config.setPassword(settings.password());
            config.setPoolName("owned-p16-gallery-reader"); config.setMaximumPoolSize(2); config.setMinimumIdle(0);
            config.setReadOnly(true); config.setConnectionTimeout(2000); config.setValidationTimeout(1000);
            config.setInitializationFailTimeout(-1); config.addDataSourceProperty("connectTimeout", "3");
            config.addDataSourceProperty("socketTimeout", "10"); pool = new HikariDataSource(config);
            reader = new ReaderSource(); jdbc = new JdbcTemplate(reader); jdbc.setQueryTimeout(3);
            var manager = new JdbcTransactionManager(reader);
            reads = new TransactionTemplate(manager); reads.setReadOnly(true); reads.setTimeout(120);
            streams = new TransactionTemplate(manager, reads); streams.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            baseline = fingerprint();
            check(baseline.get("pawbridge_animal.animals").count() == 9 && baseline.get("pawbridge_animal.apms_photo_archive").count() == 8
                    && baseline.get("public.pb_gallery_feed_fixture").count() == 1, "The exact two-source and single-marker seed counts are mandatory");
            released();
        });
    }

    @BeforeEach void requireUnchangedInputAndClearOnlyIdleCounters() {
        observed("P16 before", () -> { released(); check(fingerprint().equals(baseline), "Both input tables must retain the initial fixture"); reader.reset(); });
    }
    @AfterEach void preserveEveryInputAndReturnAllReaderResources() {
        observed("P16 after", () -> { released(); check(fingerprint().equals(baseline), "No contract may change either input table"); observations.addAll(reader.samples); });
    }
    @AfterAll void closeOnlyTheOwnedIdlePoolAndReportActualJdbcSettingsWithoutSqlOrUrls() {
        if (pool != null) {
            released(); pool.close(); check(pool.isClosed(), "The test-owned reader pool must close");
            System.out.println("P16_JDBC_OBSERVATION finalTimeoutSeconds=" + observations.stream().map(Sample::timeout).distinct().sorted().toList()
                    + " fetchSizes=" + observations.stream().map(Sample::fetch).distinct().sorted().toList());
        }
    }

    @Test void givenSelectOnlyInputs__whenStreamFailsAndRecovers__thenUsePhysicalReadOnlyRepeatableReadAndReturnAllResources() {
        observed("P16 SQL and recovery", () -> {
            var feed = feed(signer(new AtomicInteger()));
            List<Long> ids = new ArrayList<>();
            feed.streamEntries(entry -> {
                check(TransactionSynchronizationManager.isActualTransactionActive()
                        && TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                        && TransactionSynchronizationManager.hasResource(reader), "Source rows must be consumed in the bound reader transaction");
                ids.add(((Number) entry.record().get("id")).longValue());
                JsonNode photo = JSON.valueToTree(entry);
                long id = ((Number) entry.record().get("id")).longValue();
                check(IDS.contains(id), "READY/slot/current-source/species filters must precede publication");
                check(entry.record().get("species").equals(id == 996102 ? "CAT" : "DOG")
                        && entry.record().get("status").equals(id == 996102 ? "ADOPTED" : "NOTICE"), "Actual species and unfiltered status metadata must survive mapping");
                check("2026-01-02".equals(entry.record().get("happen_date")) && "Owned gallery place".equals(entry.record().get("happen_place"))
                        && "brown".equals(entry.record().get("color")) && "Owned gallery mark".equals(entry.record().get("special_mark"))
                        && "Owned gallery description".equals(entry.record().get("description")), "All six selected metadata fields must retain their fixture values");
                String expected = id == 996102 ? B : A;
                check(expected.equals(entry.record().get("source_sha256")) && expected.equals(photo.path("photo").path("sha256").asText()), "Record and archived photo digests must agree");
                check(photo.path("photo").path("key").asText().equals("apms/photos/" + expected + ".jpg"), "Only the verified archived object key may enter the feed");
                check(photo.path("photo").path("bytes").asLong() == (id == 996102 ? 200 : 100), "Stored length must survive actual SQL mapping");
                check(photo.path("photo").path("mime").asText().equals("image/jpeg"), "Stored MIME must survive actual SQL mapping");
            });
            check(ids.equals(IDS), "Actual SQL must return exactly the two eligible animals ordered by ID");
            requireReads("repeatable read", 1); released(); observations.addAll(reader.samples); reader.reset();
            expect(SyntheticFailure.class, () -> feed.streamEntries(entry -> { throw new SyntheticFailure(); }));
            requireReads("repeatable read", 1); released(); observations.addAll(reader.samples); reader.reset();
            List<Long> recovered = new ArrayList<>(); feed.streamEntries(entry -> recovered.add(((Number) entry.record().get("id")).longValue()));
            check(recovered.equals(IDS), "A fresh stream after consumer failure must return the complete unchanged source");
            requireReads("repeatable read", 1); released();
        });
    }

    @Test void givenActualReaderAndNewSnapshotDirectory__whenV1AndV2Run__thenSignOnlyAfterReadAndKeepPageLifecycleDatabaseFree(@TempDir Path directory) {
        observed("P16 phase boundaries", () -> {
            AtomicInteger signed = new AtomicInteger(); var feed = feed(signer(signed));
            var first = feed.snapshot(null); JsonNode body = JSON.readTree(first.body());
            check(body.path("complete").asBoolean() && body.path("count").asInt() == 2, "The complete legacy body must retain its two rows");
            check(body.path("photos").size() == 2 && signed.get() == 2, "The actual two-photo inventory must be signed only after its read closes");
            check(body.path("records").get(1).path("status").asText().equals("ADOPTED"), "Feed eligibility must not silently add the live-search status filter");
            requireReads("read committed", 1); released();
            int calls = signed.get(); var unchanged = feed.snapshot(first.etag());
            check(unchanged.body() == null && first.etag().equals(unchanged.etag()) && signed.get() == calls, "Unchanged content must retain ETag and skip every signing call");
            check(reader.borrowed.get() == 2, "An ETag comparison still needs its actual source read"); released();
            observations.addAll(reader.samples); reader.reset();
            AtomicInteger consumed = new AtomicInteger();
            try (var snapshots = new LostGallerySnapshots(JSON, consumer -> feed.streamEntries(entry -> {
                check(TransactionSynchronizationManager.isActualTransactionActive() && TransactionSynchronizationManager.hasResource(reader), "Snapshot rows must be written while the explicit source reader is bound");
                consumed.incrementAndGet(); consumer.accept(entry);
            }), signer(signed), directory.resolve("new-p16-snapshots"), Clock.systemUTC())) {
                var descriptor = snapshots.create(REQUEST);
                check(consumed.get() == 2 && descriptor.count() == 2 && signed.get() == calls, "Creating an immutable file must consume the source without signing or publishing a gallery");
                requireReads("repeatable read", 1); released(); observations.addAll(reader.samples); reader.reset();
                var described = snapshots.describe(descriptor.snapshotId());
                check(described.snapshotSha256().equals(descriptor.snapshotSha256()), "Describe must preserve the immutable snapshot identity");
                List<Long> ids = new ArrayList<>(); String cursor = descriptor.cursor();
                do {
                    JsonNode page = JSON.readTree(snapshots.page(descriptor.snapshotId(), cursor, 1));
                    for (JsonNode item : page.path("items")) ids.add(item.path("record").path("id").asLong());
                    check(ids.size() <= IDS.size(), "Snapshot pages must not exceed the complete source count");
                    cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asText();
                    check(page.path("complete").asBoolean() == (ids.size() == 2), "The final page must agree with the declared source count");
                } while (cursor != null);
                check(ids.equals(IDS), "Snapshot pages must retain actual SQL ordering without duplication");
                snapshots.release(descriptor.snapshotId());
                expect(LostGallerySnapshots.Gone.class, () -> snapshots.describe(descriptor.snapshotId()));
                check(reader.borrowed.get() == 0 && consumed.get() == 2, "Describe, signing, page and release must never reopen the source"); released();
            }
        });
    }

    @Test void givenProductionControllersAndNewDirectory__whenAllFiveWireRoutesRun__thenAuthenticateBeforeDatabaseSigningOrSourceWork(@TempDir Path directory) {
        observed("P16 five controller routes", () -> {
            AtomicInteger signed = new AtomicInteger(), sources = new AtomicInteger(); var feed = feed(signer(signed));
            try (var snapshots = new LostGallerySnapshots(JSON, consumer -> { sources.incrementAndGet(); feed.streamEntries(consumer); },
                    signer(signed), directory.resolve("new-p16-wire"), Clock.systemUTC())) {
                MockMvc mvc = MockMvcBuilders.standaloneSetup(new LostGalleryFeedController(feed, KEY), new LostGalleryPagesController(snapshots, KEY)).build();
                String absent = "d".repeat(32); long files = fileCount(directory);
                List<Route> routes = List.of(new Route("GET", ROOT), new Route("POST", ROOT + "/snapshots"),
                        new Route("GET", ROOT + "/snapshots/" + absent), new Route("GET", ROOT + "/snapshots/" + absent + "/pages"),
                        new Route("DELETE", ROOT + "/snapshots/" + absent));
                for (Route route : routes) for (String key : new String[]{null, "wrong", KEY + " "})
                    exchange(mvc, route.method(), route.path(), key, Map.of("cursor", "untrusted", "limit", "1"), 401);
                check(reader.borrowed.get() == 0 && sources.get() == 0 && signed.get() == 0 && fileCount(directory) == files,
                        "Missing, wrong and non-exact keys on all five routes must precede DB, signer and file-source work");
                HttpResult legacy = exchange(mvc, "GET", ROOT, KEY, Map.of(), 200);
                check(legacy.body().path("count").asInt() == 2 && "no-store".equals(legacy.cache()), "Legacy wire response must be complete and non-cacheable"); released();
                int beforeConditional = signed.get();
                var unchanged = mvc.perform(MockMvcRequestBuilders.get(ROOT).header("X-Internal-Api-Key", KEY)
                        .header("If-None-Match", legacy.etag())).andReturn().getResponse();
                check(unchanged.getStatus() == 304 && unchanged.getContentAsByteArray().length == 0
                        && legacy.etag().equals(unchanged.getHeader("ETag")) && signed.get() == beforeConditional,
                        "Actual conditional GET must return304 without any additional signing"); released();
                HttpResult created = exchange(mvc, "POST", ROOT + "/snapshots", KEY, Map.of(), 200);
                String id = created.body().path("snapshotId").asText(), cursor = created.body().path("cursor").asText();
                check(created.body().path("protocol").asText().equals("pawbridge-gallery-pages-v2") && created.body().path("count").asInt() == 2,
                        "The existing v2 descriptor wire contract must remain unchanged");
                int borrows = reader.borrowed.get();
                HttpResult described = exchange(mvc, "GET", ROOT + "/snapshots/" + id, KEY, Map.of(), 200);
                check(described.body().path("snapshotSha256").equals(created.body().path("snapshotSha256")), "Wire describe must retain the issued snapshot fingerprint");
                HttpResult first = exchange(mvc, "GET", ROOT + "/snapshots/" + id + "/pages", KEY, Map.of("cursor", cursor, "limit", "1"), 200);
                check(first.body().path("start").asInt() == 0 && first.body().path("items").size() == 1 && !first.body().path("complete").asBoolean(), "The first wire page must retain offset, limit and completion semantics");
                HttpResult last = exchange(mvc, "GET", ROOT + "/snapshots/" + id + "/pages", KEY,
                        Map.of("cursor", first.body().path("nextCursor").asText(), "limit", "1"), 200);
                check(last.body().path("start").asInt() == 1 && last.body().path("complete").asBoolean() && last.body().path("nextCursor").isNull(), "The terminal wire page must complete the same snapshot");
                exchange(mvc, "DELETE", ROOT + "/snapshots/" + id, KEY, Map.of(), 204);
                exchange(mvc, "GET", ROOT + "/snapshots/" + id, KEY, Map.of(), 410);
                check(reader.borrowed.get() == borrows && sources.get() == 1, "Wire page/describe/release must not consume another source or DB connection"); released();
            }
        });
    }

    private LostGalleryFeed feed(Function<String,String> signer) { return new LostGalleryFeed(jdbc, JSON, signer, reads, streams); }
    private Function<String,String> signer(AtomicInteger calls) {
        return key -> {
            released(); check(key.matches("apms/photos/[a-f0-9]{64}\\.(jpg|png|webp)"), "The signer may receive only verified archive keys");
            calls.incrementAndGet(); return "https://owned-p16.invalid/pawbridge-animal-originals/" + key;
        };
    }
    private void requireReads(String isolation, int expectedBorrows) {
        check(reader.borrowed.get() == expectedBorrows && !reader.samples.isEmpty(), "One source read must use the expected number of physical borrows");
        check(reader.samples.stream().allMatch(s -> s.readOnly() && isolation.equals(s.isolation()) && s.bound() && s.manualCommit()), "Actual PostgreSQL transaction mode and same-reader binding must match the selected phase");
        check(reader.samples.stream().map(Sample::pid).distinct().count() == 1, "Conflict and source SQL must share the same PostgreSQL backend");
        check(reader.samples.stream().anyMatch(Sample::rows) && reader.samples.stream().filter(Sample::rows).allMatch(s -> s.fetch() == 200), "Actual source statements must keep PostgreSQL cursor fetch size200");
        check(reader.timeoutRequests.contains(20), "The original setQueryTimeout20 call must remain present");
        check(reader.samples.stream().allMatch(s -> s.timeout() > 0 && s.timeout() <= 120), "Final timeout must be observed within the supplied transaction budget, not assumed to equal20");
    }
    private void released() {
        check(!TransactionSynchronizationManager.isActualTransactionActive() && (reader == null || !TransactionSynchronizationManager.hasResource(reader))
                && (pool == null || !TransactionSynchronizationManager.hasResource(pool)), "No caller transaction or reader binding may escape a phase");
        if (reader != null) check(reader.borrowed.get() == reader.returned.get() && reader.statements.get() == reader.closedStatements.get()
                && reader.results.get() == reader.closedResults.get(), "Every borrowed connection, statement and result cursor must be returned");
        if (pool != null) check(pool.getHikariPoolMXBean().getActiveConnections() == 0 && pool.getHikariPoolMXBean().getThreadsAwaitingConnection() == 0,
                "The test-owned reader pool must have no active or waiting borrower");
    }
    private Map<String,Fingerprint> fingerprint() {
        Map<String,Fingerprint> result = new LinkedHashMap<>();
        TABLES.forEach((table, key) -> {
            var row = probe.queryForMap("SELECT count(*) AS n,md5(COALESCE(string_agg(row_to_json(t)::text,',' ORDER BY " + key + "),'')) AS digest FROM " + table + " t");
            result.put(table, new Fingerprint(((Number) row.get("n")).longValue(), (String) row.get("digest")));
        }); return result;
    }
    private void verifyServer() {
        var id = probe.queryForMap("SELECT current_database() AS db,current_user AS actor,session_user AS session,current_setting('server_version_num') AS version,(SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname=current_database()) AS owner");
        check(settings.database().equals(id.get("db")) && settings.username().equals(id.get("actor")) && settings.username().equals(id.get("session"))
                && "170011".equals(id.get("version")) && !settings.username().equals(id.get("owner")), "Only the isolated PostgreSQL17.11 database and separate reader are accepted");
        List<String> markers = probe.query("SELECT marker FROM public.pb_gallery_feed_fixture", (r, n) -> r.getString(1));
        check(markers.size() == 1 && settings.marker().equals(markers.getFirst()), "The exact single P16 marker is mandatory");
        check(Boolean.TRUE.equals(probe.queryForObject("SELECT NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls AND NOT rolinherit AND NOT EXISTS (SELECT 1 FROM pg_auth_members m WHERE m.member=pg_roles.oid) AND NOT pg_has_role(current_user,(SELECT datdba FROM pg_database WHERE datname=current_database()),'MEMBER') FROM pg_roles WHERE rolname=current_user", Boolean.class)), "The reader must have no owner or administrator inheritance");
        for (String schema : List.of("public", "pawbridge_animal")) check(Boolean.FALSE.equals(probe.queryForObject("SELECT has_schema_privilege(current_user,?,'CREATE')", Boolean.class, schema)), "The reader may not create schema objects");
        check(Boolean.FALSE.equals(probe.queryForObject("SELECT has_database_privilege(current_user,current_database(),'CREATE,TEMP')", Boolean.class)), "The reader must have no database or temporary-object creation privilege");
        for (String table : TABLES.keySet()) check(Boolean.TRUE.equals(probe.queryForObject("SELECT has_table_privilege(current_user,?,'SELECT') AND NOT has_table_privilege(current_user,?,'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')", Boolean.class, table, table)), "Only SELECT may be granted on both source tables");
        check(probe.queryForObject("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname !~ '^pg_' AND n.nspname<>'information_schema' AND c.relkind IN('r','p','v','m','f') AND (has_table_privilege(current_user,c.oid,'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') OR has_any_column_privilege(current_user,c.oid,'INSERT,UPDATE,REFERENCES') OR ((has_table_privilege(current_user,c.oid,'SELECT') OR has_any_column_privilege(current_user,c.oid,'SELECT')) AND (n.nspname||'.'||c.relname) NOT IN ('public.pb_gallery_feed_fixture','pawbridge_animal.animals','pawbridge_animal.apms_photo_archive')))", Long.class) == 0L, "The reader must have no application writes or unrelated application reads");
        check(Boolean.FALSE.equals(probe.queryForObject("SELECT has_table_privilege(current_user,'pawbridge_animal.shelters','SELECT') OR has_any_column_privilege(current_user,'pawbridge_animal.shelters','SELECT')", Boolean.class)), "The shelter FK seed table must remain unreadable by the runtime reader");
        check(probe.queryForObject("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname !~ '^pg_' AND n.nspname<>'information_schema' AND c.relkind='S' AND has_sequence_privilege(current_user,c.oid,'USAGE,SELECT,UPDATE')", Long.class) == 0L, "The reader must have no application sequence privilege");
        List<String> tables = probe.query("SELECT tablename FROM pg_tables WHERE schemaname='pawbridge_animal' ORDER BY tablename", (r, n) -> r.getString(1));
        check(tables.equals(List.of("animals", "apms_photo_archive", "shelters")), "P16 must preserve the original shelter FK table and only the two selected source tables");
    }

    private final class ReaderSource extends AbstractDataSource {
        final AtomicInteger borrowed = new AtomicInteger(), returned = new AtomicInteger(), statements = new AtomicInteger(), closedStatements = new AtomicInteger(), results = new AtomicInteger(), closedResults = new AtomicInteger();
        final List<Sample> samples = new ArrayList<>(); final List<Integer> timeoutRequests = new ArrayList<>();
        @Override public Connection getConnection() throws SQLException {
            Connection connection = pool.getConnection(); borrowed.incrementAndGet(); AtomicBoolean closed = new AtomicBoolean();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                if (method.getName().equals("close")) { if (!closed.get()) { call(connection, method, args); closed.set(true); returned.incrementAndGet(); } return null; }
                Object value = call(connection, method, args);
                if (method.getName().equals("prepareStatement") && value instanceof PreparedStatement statement && args[0] instanceof String sql)
                    return statement(connection, statement, sql);
                return value;
            });
        }
        @Override public Connection getConnection(String username, String password) throws SQLException { throw new SQLException("Explicit credential override is not supported"); }
        private PreparedStatement statement(Connection connection, PreparedStatement statement, String sql) {
            statements.incrementAndGet(); AtomicBoolean closed = new AtomicBoolean();
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> {
                if (method.getName().equals("close")) { if (!closed.get()) { call(statement, method, args); closed.set(true); closedStatements.incrementAndGet(); } return null; }
                if (method.getName().equals("setQueryTimeout")) timeoutRequests.add((Integer) args[0]);
                if (method.getName().equals("executeQuery") && sql.contains("pawbridge_animal.apms_photo_archive")) {
                    try (var inspection = connection.createStatement(); var row = inspection.executeQuery("SELECT current_setting('transaction_read_only'),current_setting('transaction_isolation'),pg_backend_pid()")) {
                        check(row.next(), "Physical reader settings must be observable on the actual borrowed connection");
                        samples.add(new Sample("on".equals(row.getString(1)), row.getString(2), row.getInt(3), statement.getFetchSize(), statement.getQueryTimeout(),
                                !sql.startsWith("SELECT stored_sha256"), TransactionSynchronizationManager.hasResource(ReaderSource.this), !connection.getAutoCommit()));
                    }
                }
                Object value = call(statement, method, args);
                if (value instanceof ResultSet result) return result(result);
                return value;
            });
        }
        private ResultSet result(ResultSet result) {
            results.incrementAndGet(); AtomicBoolean closed = new AtomicBoolean();
            return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class}, (proxy, method, args) -> {
                if (method.getName().equals("close")) { if (!closed.get()) { call(result, method, args); closed.set(true); closedResults.incrementAndGet(); } return null; }
                return call(result, method, args);
            });
        }
        void reset() { released(); borrowed.set(0); returned.set(0); statements.set(0); closedStatements.set(0); results.set(0); closedResults.set(0); samples.clear(); timeoutRequests.clear(); }
    }

    private static HttpResult exchange(MockMvc mvc, String method, String path, String key, Map<String,String> params, int expected) throws Exception {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(method), path).accept(MediaType.APPLICATION_JSON);
        if (key != null) request.header("X-Internal-Api-Key", key);
        if (method.equals("POST")) request.header("X-Gallery-Request-Id", REQUEST);
        params.forEach(request::param);
        var response = mvc.perform(request).andReturn().getResponse();
        check(response.getStatus() == expected, "Unexpected owned gallery wire status");
        byte[] bytes = response.getContentAsByteArray(); return new HttpResult(bytes.length == 0 ? JSON.getNodeFactory().nullNode() : JSON.readTree(bytes), response.getHeader("Cache-Control"), response.getHeader("ETag"));
    }
    private static long fileCount(Path root) throws Exception { try (var files = Files.walk(root)) { return files.filter(Files::isRegularFile).count(); } }
    private static Object call(Object target, Method method, Object[] args) throws Throwable { try { return method.invoke(target, args); } catch (InvocationTargetException failure) { throw failure.getCause(); } }
    private static void observed(String phase, Checked action) { try { action.run(); } catch (AssertionError failure) { throw failure; } catch (Throwable failure) { throw new AssertionError("Owned gallery observation failed: " + phase + "; type=" + failure.getClass().getName()); } }
    private static void expect(Class<? extends Throwable> type, Checked action) { try { action.run(); } catch (Throwable failure) { check(type.isInstance(failure), "Unexpected owned synthetic failure type"); return; } throw new AssertionError("Expected owned synthetic failure was absent"); }
    private static void check(boolean value, String fixed) { if (!value) throw new AssertionError(fixed); }
    private interface Checked { void run() throws Exception; }
    private static final class SyntheticFailure extends RuntimeException { }
    private record Fingerprint(long count, String digest) { }
    private record HttpResult(JsonNode body, String cache, String etag) { }
    private record Route(String method, String path) { }
    private record Sample(boolean readOnly, String isolation, int pid, int fetch, int timeout, boolean rows, boolean bound, boolean manualCommit) { }

    private record Settings(String url, String database, String username, String password, String marker) {
        static Settings load() throws Exception {
            String value = System.getenv("P16_PG_SETTINGS"); check(value != null && !value.isBlank(), "P16_PG_SETTINGS is mandatory and never causes a skip");
            Path file = Path.of(value);
            check(file.isAbsolute() && file.normalize().equals(file) && file.getFileName().toString().equals("gallery-feed.properties") && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file), "Only the exact absolute P16 settings file is accepted");
            check(Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS).equals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)) && ((Number) Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).intValue() == 1, "P16 settings must have0600 and one hard link");
            check(System.getProperty("user.name").equals(Files.getOwner(file).getName()) && Files.getOwner(file).equals(Files.getOwner(file.getParent())), "Settings and parent must belong to the current process owner");
            for (Path parent = file.getParent(); parent != null; parent = parent.getParent()) check(!Files.isSymbolicLink(parent), "Settings ancestors cannot be symbolic links");
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file.getParent(), LinkOption.NOFOLLOW_LINKS);
            check(permissions.equals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)), "P16 settings parent must retain exact0700 permissions");
            Properties values = new Properties(); try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { values.load(input); }
            String database = required(values, "database"), url = required(values, "jdbcUrl"), username = required(values, "username"), marker = required(values, "marker");
            check(database.matches("pb_gallery_feed_owned_[0-9a-f]{12}") && url.equals("jdbc:postgresql://127.0.0.1:52620/" + database), "Only the exact isolated P16 loopback database is accepted");
            check(username.equals("pb_gallery_feed_reader") && marker.matches("[0-9a-f]{32}"), "Owned reader and marker identities are mandatory");
            check(values.stringPropertyNames().equals(Set.of("jdbcUrl", "username", "password", "database", "marker")), "P16 settings must retain exactly the five approved fields");
            return new Settings(url, database, username, required(values, "password"), marker);
        }
        private static String required(Properties values, String key) { String result = values.getProperty(key); check(result != null && !result.isBlank(), "Every owned setting is mandatory"); return result; }
        @Override public String toString() { return "OwnedGallerySettings[redacted]"; }
    }
}
