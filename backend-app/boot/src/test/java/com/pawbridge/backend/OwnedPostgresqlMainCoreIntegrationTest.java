package com.pawbridge.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.animal.internal.discovery.infrastructure.KoreanSearchAnalyzer;
import com.pawbridge.backend.boot.configuration.PostWriteSettings;
import com.pawbridge.backend.boot.configuration.ReadDatabaseSettings;
import com.pawbridge.backend.store.internal.catalog.infrastructure.KoreanSearchTerms;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.IMetricsTracker;
import jakarta.persistence.EntityManagerFactory;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.mock.web.MockServletContext;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.ConfigurableTransactionManager;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.support.GenericWebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** One fresh SELECT-only fixture, actual Main and module APIs; no business/DataSource substitutes. */
@Tag("owned-postgresql")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(60)
class OwnedPostgresqlMainCoreIntegrationTest {
    private static final String SQL_SHA = "c29b9adc5c085fd8d31d1ab3eb019a369d64d29f73debfbf78055319ccfaa294";
    private static final String RESUME_SHA = "0c471fd664f107524e395b0d414c3e1a1c107f007e1f512194f0562f97c91e47";
    private static final String CID = "b1719e47b8c3ff8c334e9df96c86b4f36478eb72022b8e19278d75c9f8a7187c";
    private static final String IMAGE = "sha256:cf134a767f474095eeba57e0117be8e568e011a63f33fbf252f14c9b760f8e6f";
    private static final String OWNER = "08b97b84-e0b5-4c6f-a5f8-3d51ec4a2f89";
    private static final Path RESUME = Path.of("/private/tmp/pawbridge-main-db-integration-owned-20261021/owned-resume-receipt.json");
    private static final Map<String,String> KEYS = Map.ofEntries(
            Map.entry("pawbridge_user.users", "user_id"), Map.entry("pawbridge_user.contact_deletions", "user_id"),
            Map.entry("pawbridge_user.favorites", "favorite_id"), Map.entry("pawbridge_community.posts", "post_id"),
            Map.entry("pawbridge_animal.shelters", "id"), Map.entry("pawbridge_animal.animals", "id"),
            Map.entry("pawbridge_store.categories", "id"), Map.entry("pawbridge_store.option_groups", "id"),
            Map.entry("pawbridge_store.option_values", "id"), Map.entry("pawbridge_store.products", "id"),
            Map.entry("pawbridge_store.product_skus", "id"), Map.entry("pawbridge_store.sku_values", "id"),
            Map.entry("pawbridge_store.wishlists", "id"), Map.entry("pawbridge_store.orders", "id"),
            Map.entry("pawbridge_store.order_items", "id"), Map.entry("pawbridge_store.outbox", "id"),
            Map.entry("pawbridge_store.product_search_documents", "product_id"));
    private final ObjectMapper json = new ObjectMapper();
    private final Resources resources = new Resources();
    private final Map<ch.qos.logback.classic.Logger,ch.qos.logback.classic.Level> levels = new LinkedHashMap<>();
    private Settings settings;
    private GenericWebApplicationContext context;
    private HikariDataSource reader, writer;
    private JdbcTemplate audit;
    private MockMvc mvc;
    private Map<String,String> baseline;
    private int nextOrder = 1;
    private boolean nextAllowed, caseEntered, completedBody;

    @BeforeAll
    void given__fresh_select_only_p22__when__actual_main_starts__then__use_exact_roles_and_resources() {
        observed("setup", () -> {
            quiet();
            settings = Settings.load(json);
            try (InputStream input = getClass().getResourceAsStream("/owned-postgresql/main-core-owned-schema.sql")) {
                check(input != null && sha(input.readAllBytes()).equals(SQL_SHA), "Exact frozen P22 schema is required");
            }
            var values = F3TestInputs.values();
            values.put("F2_JDBC_URL", settings.url());
            values.put("F2_DB_USER", settings.reader());
            values.put("F2_DB_PASSWORD", settings.password());
            values.put("F2_POOL_MAX_SIZE", "1");
            values.put("F3_DB_WRITER_USER", settings.writer());
            values.put("F3_DB_WRITER_PASSWORD", settings.writerPassword());
            var read = ReadDatabaseSettings.fromEnvironment(values::get);
            var write = PostWriteSettings.fromEnvironment(values::get, read);
            context = new GenericWebApplicationContext();
            context.setServletContext(new MockServletContext());
            TestPropertyValues.of(CORE_FLAGS).applyTo(context);
            context.getBeanFactory().registerSingleton("readDatabaseSettings", read);
            context.getBeanFactory().registerSingleton("postWriteSettings", write);
            context.getBeanFactory().addBeanPostProcessor(resources);
            new AnnotatedBeanDefinitionReader(context).register(PawBridgeApplication.class);
            context.refresh();
            reader = context.getBean("dataSource", HikariDataSource.class);
            writer = context.getBean("writeDataSource", HikariDataSource.class);
            check(reader != writer && resources.pools.size() == 2 && resources.factories.size() == 3,
                    "Actual Main must own the two named pools and three factories");
            check(reader.getUsername().equals(settings.reader()) && writer.getUsername().equals(settings.writer())
                    && reader.getJdbcUrl().equals(settings.url()) && writer.getJdbcUrl().equals(settings.url()),
                    "Actual Main settings must select the two distinct P22 identities on the same database");
            audit = new JdbcTemplate(reader);
            audit.setQueryTimeout(5);
            verifyTarget(reader, false);
            verifyTarget(writer, true);
            check(audit.query("SELECT schemaname||'.'||tablename FROM pg_tables WHERE schemaname IN "
                    + "('pawbridge_store','pawbridge_user','pawbridge_animal','pawbridge_community') ORDER BY 1",
                    (row, n) -> row.getString(1)).equals(KEYS.keySet().stream().sorted().toList()),
                    "Only the exact seventeen selected source tables may exist");
            baseline = fingerprints();
            mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
            released();
            nextAllowed = true;
        });
    }

    @BeforeEach
    void given__previous_case_succeeded__when__next_case_starts__then__fence_partial_or_failed_runs(TestInfo info) {
        boolean previous = nextAllowed;
        nextAllowed = false; completedBody = false; caseEntered = false;
        check(previous && info.getTestMethod().orElseThrow().getAnnotation(Order.class).value() == nextOrder,
                "Full ordered P22 class and previous success are mandatory");
        released(); resources.events.clear(); caseEntered = true;
    }

    @AfterEach
    void given__request_ended__when__auditing__then__preserve_all_tables_and_return_every_resource() {
        if (!caseEntered) return;
        released();
        check(baseline.equals(fingerprints()), "Every selected source row must remain unchanged");
        released();
        check(completedBody, "Failed P22 request fences all later requests");
        nextOrder++; nextAllowed = true;
    }

    @AfterAll
    void given__requests_settled__when__main_closes__then__close_owned_resources_without_dropping_data() {
        try {
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (!resources.idle() && System.nanoTime() < deadline) {
                try { Thread.sleep(20); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); break; }
            }
            check(resources.idle() && !TransactionSynchronizationManager.isActualTransactionActive()
                    && resources.pools.stream().noneMatch(TransactionSynchronizationManager::hasResource),
                    "Active requests remain; do not close their pools or discard the fixture");
            if (context != null) context.close();
            check(resources.pools.stream().allMatch(HikariDataSource::isClosed), "Actual Main pools must close");
            check(resources.factories.stream().noneMatch(EntityManagerFactory::isOpen), "Actual Main factories must close");
            for (var analyzer : resources.animalAnalyzers) expectClosed(() -> analyzer.tokens("진돗개"));
            for (var analyzer : resources.storeAnalyzers) expectClosed(() -> analyzer.query("강아지 사료"));
            check(nextOrder == 4 && nextAllowed, "Exactly all three P22 requests must complete");
        } finally {
            levels.forEach(ch.qos.logback.classic.Logger::setLevel);
        }
    }

    @Test @Order(1)
    void given__own_and_other_favorites__when__jwt_enters_user_module__then__read_actual_animal_batch_with_independent_reader() {
        contract("User Animal read", () -> {
            JsonNode data = success("/api/v1/users/me/favorite-animals");
            check(data.path("userId").asLong() == 910001 && data.path("totalCount").asInt() == 1
                    && data.path("favorites").size() == 1, "Only the authenticated member's favorite may return");
            JsonNode item = data.path("favorites").get(0);
            check(item.path("favoriteId").asLong() == 510001 && item.path("animalId").asLong() == 420001
                    && item.path("breed").asText().equals("p22-breed-a")
                    && item.path("shelterName").asText().equals("p22-shelter-a")
                    && item.path("status").asText().equals("PROTECT"),
                    "Actual module batch and joined shelter must supply the selected Animal data");
            var writes = begins("writeTransactionManager"); var reads = begins("transactionManager");
            check(writes.size() == 1 && reads.size() == 1
                    && writes.getFirst().actor().equals(settings.writer()) && reads.getFirst().actor().equals(settings.reader())
                    && writes.getFirst().pid() != reads.getFirst().pid(),
                    "User RO writer transaction and Animal independent reader must use distinct real connections");
            check(resources.events.stream().allMatch(Event::readOnly), "Both actual module transactions must be read-only");
        });
    }

    @Test @Order(2)
    void given__actual_user_and_purchase_snapshots__when__jwt_reads_mypage__then__finish_user_lookup_before_store_ro_page() {
        contract("User Store read", () -> {
            JsonNode page = success("/api/v1/users/me/orders?size=20&sort=id,asc");
            check(page.path("totalElements").asLong() == 7 && page.path("content").size() == 7,
                    "Only this member's seven purchase parents may return");
            var ids = new HashSet<Long>(); JsonNode selected = null;
            for (JsonNode row : page.path("content")) {
                ids.add(row.path("orderId").asLong());
                if (row.path("orderId").asLong() == 102) selected = row;
            }
            check(ids.equals(Set.of(101L,102L,103L,104L,105L,106L,107L)) && selected != null,
                    "Another member's later order must never enter the User page");
            check(selected.path("items").size() == 2 && selected.path("totalAmount").asLong() == 300,
                    "Purchase snapshots must retain both selected items and their original amount");
            var prices = new TreeSet<Long>();
            for (JsonNode item : selected.path("items")) {
                prices.add(item.path("price").asLong());
                check(item.path("productName").asText().startsWith("p21 snapshot102"), "Live product data cannot replace purchase snapshots");
            }
            check(prices.equals(Set.of(100L,200L)), "Purchase item prices must retain their exact snapshots");
            var user = begins("transactionManager"); var store = begins("storeMemberJpaTransactionManager");
            check(user.size() == 1 && store.size() == 1 && store.getFirst().isolation().equals("repeatable read"),
                    "Actual User lookup and Store reader JPA/RR must each own their read transaction");
            check(index("transactionManager", "commit") < index("storeMemberJpaTransactionManager", "begin"),
                    "User transaction completion must precede the Store module call");
        });
    }

    @Test @Order(3)
    void given__community_author_and_real_user_nickname__when__reading_the_post__then__cross_module_lookup_avoids_fallback() {
        contract("Community User read", () -> {
            JsonNode post = success("/api/v1/posts/read/810001");
            check(post.path("postId").asLong() == 810001 && post.path("authorId").asLong() == 910001
                    && post.path("authorNickname").asText().equals("p22-nick-910001")
                    && post.path("title").asText().equals("p22-post-a"),
                    "Actual User nickname must be returned rather than the unavailable-user fallback");
            check(begins("transactionManager").size() == 2
                    && resources.events.stream().allMatch(e -> e.actor().equals(settings.reader()) && e.readOnly()),
                    "Community post and User nickname must complete actual reader transactions");
        });
    }

    private JsonNode success(String path) throws Exception {
        var response = mvc.perform(get(path).header("Authorization", "Bearer " + F3TestInputs.token(910001, "ROLE_USER")))
                .andExpect(status().isOk()).andReturn();
        check(response.getHandler() != null, "Actual MVC handler must consume the successful request");
        JsonNode result = json.readTree(response.getResponse().getContentAsByteArray());
        check(result.path("code").asInt() == 200,
                "Existing successful response envelope must be preserved");
        return result.path("data");
    }
    private List<Event> begins(String manager) { return resources.events.stream().filter(e -> e.manager().equals(manager) && e.phase().equals("begin")).toList(); }
    private int index(String manager, String phase) {
        for (int i = 0; i < resources.events.size(); i++) if (resources.events.get(i).manager().equals(manager) && resources.events.get(i).phase().equals(phase)) return i;
        throw new AssertionError("Required actual transaction event is absent");
    }
    private Map<String,String> fingerprints() {
        var result = new TreeMap<String,String>();
        KEYS.forEach((table,key) -> result.put(table, audit.queryForObject("SELECT count(*)::text||':'||md5(COALESCE(string_agg(row_to_json(t)::text,',' ORDER BY " + key + "),'')) FROM " + table + " t", String.class)));
        return result;
    }
    private void released() {
        check(!TransactionSynchronizationManager.isActualTransactionActive() && resources.idle() && resources.timeouts.get() == 0
                && resources.pools.stream().noneMatch(TransactionSynchronizationManager::hasResource),
                "Every actual Main transaction and pool borrow must return before later work");
    }
    private void verifyTarget(HikariDataSource source, boolean writingName) {
        var jdbc = new JdbcTemplate(source); jdbc.setQueryTimeout(5);
        var row = jdbc.queryForMap("SELECT current_database() db,current_user actor,session_user session,current_setting('server_version_num') version,(SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname=current_database()) owner");
        String actor = writingName ? settings.writer() : settings.reader();
        check(settings.db().equals(row.get("db")) && actor.equals(row.get("actor")) && actor.equals(row.get("session"))
                && settings.owner().equals(row.get("owner")) && "170011".equals(row.get("version")), "Exact P22 database, role and PG17.11 are mandatory");
        check(settings.marker().equals(jdbc.queryForObject("SELECT marker FROM public.pb_main_core_fixture", String.class)), "Exact P22 marker is required");
        check(Boolean.TRUE.equals(jdbc.queryForObject("SELECT has_table_privilege(current_user,'public.pb_main_core_fixture','SELECT') AND NOT has_table_privilege(current_user,'public.pb_main_core_fixture','INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')", Boolean.class)), "Marker remains SELECT-only");
        check(Boolean.TRUE.equals(jdbc.queryForObject("SELECT rolcanlogin AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls AND NOT rolinherit AND NOT EXISTS(SELECT 1 FROM pg_auth_members WHERE member=pg_roles.oid) FROM pg_roles WHERE rolname=current_user", Boolean.class)), "Runtime cannot own or inherit administrative capability");
        check(Boolean.TRUE.equals(jdbc.queryForObject("SELECT NOT rolcanlogin AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls AND NOT rolinherit FROM pg_roles WHERE rolname=?", Boolean.class, settings.owner()))
                && Boolean.FALSE.equals(jdbc.queryForObject("SELECT pg_has_role(current_user,?,'MEMBER')", Boolean.class, settings.owner())), "Separate NOLOGIN owner must remain inaccessible");
        check(Boolean.FALSE.equals(jdbc.queryForObject("SELECT has_database_privilege(current_user,current_database(),'CREATE,TEMP')", Boolean.class)), "Runtime database and TEMP DDL are forbidden");
        for (String schema : List.of("public","pawbridge_user","pawbridge_store","pawbridge_animal","pawbridge_community"))
            check(Boolean.FALSE.equals(jdbc.queryForObject("SELECT has_schema_privilege(current_user,?,'CREATE')", Boolean.class, schema)), "Runtime schema creation is forbidden");
        for (String table : KEYS.keySet()) {
            for (String right : List.of("INSERT","UPDATE","DELETE","TRUNCATE","REFERENCES","TRIGGER"))
                check(Boolean.FALSE.equals(jdbc.queryForObject("SELECT has_table_privilege(current_user,(SELECT c.oid FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname||'.'||c.relname=?),?)", Boolean.class, table, right)), "Selected tables must have no runtime DML rights");
            for (String right : List.of("INSERT","UPDATE","REFERENCES"))
                check(Boolean.FALSE.equals(jdbc.queryForObject("SELECT has_any_column_privilege(current_user,(SELECT c.oid FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname||'.'||c.relname=?),?)", Boolean.class, table, right)), "Column grants cannot bypass read-only access");
            boolean select = Boolean.TRUE.equals(jdbc.queryForObject("SELECT has_table_privilege(current_user,(SELECT c.oid FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname||'.'||c.relname=?),'SELECT')", Boolean.class, table));
            check(select == (!writingName || table.equals("pawbridge_user.favorites")), "Only each identity's selected read tables may be accessible");
        }
        check(Boolean.TRUE.equals(jdbc.queryForObject("SELECT has_column_privilege(current_user,'pawbridge_user.users','user_id','SELECT')", Boolean.class)), "Writer User existence lookup must be available");
        for (String column : List.of("email","name","nickname","provider","role","created_at"))
            check(Boolean.valueOf(!writingName).equals(jdbc.queryForObject("SELECT has_column_privilege(current_user,'pawbridge_user.users',?,'SELECT')", Boolean.class, column)), "Writer must not inherit unrelated User profile columns");
        var sequences = jdbc.query("SELECT n.nspname||'.'||c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname IN('pawbridge_store','pawbridge_user','pawbridge_animal','pawbridge_community') AND c.relkind='S'", (rs,n) -> rs.getString(1));
        check(sequences.size() == 13, "Exactly selected identity sequences are required");
        for (String sequence : sequences) check(Boolean.FALSE.equals(jdbc.queryForObject("SELECT has_sequence_privilege(current_user,(SELECT c.oid FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname||'.'||c.relname=?),'USAGE,SELECT,UPDATE')", Boolean.class, sequence)), "Neither runtime identity may use source sequences");
        check(jdbc.queryForObject("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname IN('public','pawbridge_store','pawbridge_user','pawbridge_animal','pawbridge_community') AND c.relkind IN('r','S','v') AND pg_get_userbyid(c.relowner)<>?", Long.class, settings.owner()) == 0, "All new fixture objects retain their inaccessible owner");
    }

    private record Event(String manager, String phase, int pid, String actor, boolean readOnly, String isolation) { }
    private final class Resources implements BeanPostProcessor {
        final List<HikariDataSource> pools = new CopyOnWriteArrayList<>();
        final List<EntityManagerFactory> factories = new CopyOnWriteArrayList<>();
        final List<KoreanSearchAnalyzer> animalAnalyzers = new CopyOnWriteArrayList<>();
        final List<KoreanSearchTerms> storeAnalyzers = new CopyOnWriteArrayList<>();
        final List<Event> events = new CopyOnWriteArrayList<>();
        final AtomicInteger active = new AtomicInteger(), timeouts = new AtomicInteger();
        @Override public Object postProcessAfterInitialization(Object bean, String name) {
            if (bean instanceof HikariDataSource pool) {
                pools.add(pool);
                pool.setMetricsTrackerFactory((poolName, stats) -> new IMetricsTracker() {
                    @Override public void recordConnectionAcquiredNanos(long elapsed) { active.incrementAndGet(); }
                    @Override public void recordConnectionUsageMillis(long elapsed) { active.decrementAndGet(); }
                    @Override public void recordConnectionTimeout() { timeouts.incrementAndGet(); }
                });
            }
            if (bean instanceof EntityManagerFactory factory) factories.add(factory);
            if (bean instanceof KoreanSearchAnalyzer analyzer) animalAnalyzers.add(analyzer);
            if (bean instanceof KoreanSearchTerms analyzer) storeAnalyzers.add(analyzer);
            if (bean instanceof ConfigurableTransactionManager manager) {
                HikariDataSource source = bean instanceof JdbcTransactionManager jdbc ? (HikariDataSource) jdbc.getDataSource()
                        : bean instanceof JpaTransactionManager jpa ? (HikariDataSource) jpa.getDataSource() : null;
                if (source != null) manager.addListener(new TransactionExecutionListener() {
                    @Override public void afterBegin(TransactionExecution tx, Throwable failure) {
                        if (failure != null) return;
                        Object bound = TransactionSynchronizationManager.getResource(source);
                        check(bound instanceof ConnectionHolder, "Actual transaction must bind the named datasource");
                        try (var statement = ((ConnectionHolder) bound).getConnection().createStatement()) {
                            statement.setQueryTimeout(3);
                            try (var row = statement.executeQuery("SELECT current_database(),current_user,pg_backend_pid(),current_setting('transaction_read_only'),current_setting('transaction_isolation')")) {
                                check(row.next() && settings.db().equals(row.getString(1)) && tx.isReadOnly()
                                        && "on".equals(row.getString(4)) && !((ConnectionHolder) bound).getConnection().getAutoCommit(), "Actual transaction must select this P22 database read-only");
                                events.add(new Event(name,"begin",row.getInt(3),row.getString(2),true,row.getString(5)));
                            }
                        } catch (SQLException failed) { throw new AssertionError("Read-only transaction probe failed"); }
                    }
                    @Override public void afterCommit(TransactionExecution tx, Throwable failure) {
                        check(failure == null, "Main request transaction commit must succeed");
                        events.add(new Event(name,"commit",0,source.getUsername(),tx.isReadOnly(),""));
                    }
                });
            }
            return bean;
        }
        boolean idle() {
            return active.get() == 0 && pools.stream().allMatch(p -> p.getHikariPoolMXBean() == null || p.getHikariPoolMXBean().getActiveConnections() == 0);
        }
    }

    private record Settings(String url,String db,String reader,String password,String writer,String writerPassword,String marker) {
        String owner() { return db.replace("pb_main_core_owned_","pb_main_core_owner_"); }
        static Settings load(ObjectMapper json) throws Exception {
            check("P22_PG_SETTINGS".equals(System.getenv("PAWBRIDGE_OWNED_SETTINGS_ENV")), "Only explicit P22 selector is accepted");
            String configured = System.getenv("P22_PG_SETTINGS");
            check(configured != null && !configured.isBlank(), "P22 settings are mandatory without skip or fallback");
            Path file = Path.of(configured), expected = Path.of("/private/tmp/pawbridge-owned-pg-night-20261020/main-core/main-core.properties");
            check(file.equals(expected) && file.normalize().equals(file), "Only exact fresh Main settings path is accepted");
            privateFile(file); privateFile(RESUME);
            check(sha(Files.readAllBytes(RESUME)).equals(RESUME_SHA), "Only the current Root resume receipt is accepted");
            var resume = json.readTree(Files.readAllBytes(RESUME));
            check(CID.equals(resume.path("cid").asText()) && IMAGE.equals(resume.path("image").asText())
                    && OWNER.equals(resume.path("owner").asText()) && "127.0.0.1".equals(resume.path("host").asText())
                    && resume.path("port").asInt() == 55437 && resume.path("ready").asBoolean()
                    && !resume.path("oldTmpfsRowsReused").asBoolean() && resume.path("noHostMount").asBoolean(), "Current Root owned identity must match");
            check(Files.getPosixFilePermissions(file.getParent()).equals(Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE,PosixFilePermission.OWNER_EXECUTE)), "Exclusive claim0700 required");
            var properties = new Properties(); try (var input = Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)) { properties.load(input); }
            check(properties.stringPropertyNames().equals(Set.of("jdbcUrl","database","username","password","writerUsername","writerPassword","marker")), "Exactly seven private fields are accepted");
            String db = required(properties,"database"), reader = required(properties,"username"), writer = required(properties,"writerUsername"), marker = required(properties,"marker"), url = required(properties,"jdbcUrl");
            check(db.matches("pb_main_core_owned_[0-9a-f]{12}") && reader.equals(db.replace("pb_main_core_owned_","pb_main_core_reader_"))
                    && writer.equals(db.replace("pb_main_core_owned_","pb_main_core_writer_")) && !reader.equals(writer)
                    && url.equals("jdbc:postgresql://127.0.0.1:55437/"+db) && marker.matches("[0-9a-f]{32}"), "Only exact nonce roles/database/loopback/marker are accepted");
            Path state = file.resolveSibling("state.json"); privateFile(state); var claim = json.readTree(Files.readAllBytes(state));
            check(db.equals(claim.path("database").asText()) && reader.equals(claim.path("reader").asText()) && writer.equals(claim.path("writer").asText())
                    && marker.equals(claim.path("marker").asText()) && "main-core-v1".equals(claim.path("purpose").asText())
                    && claim.path("schemaInitialized").asBoolean() && SQL_SHA.equals(claim.path("schema_sha256").asText())
                    && RESUME_SHA.equals(claim.path("resume_receipt_sha256").asText()) && claim.path("port").asInt() == 55437
                    && CID.equals(claim.path("containerId").asText()) && IMAGE.equals(claim.path("image").asText()) && OWNER.equals(claim.path("owner").asText()), "Fresh claim must bind exact SQL/Root receipt and owned runtime");
            return new Settings(url,db,reader,required(properties,"password"),writer,required(properties,"writerPassword"),marker);
        }
        private static String required(Properties p,String key) { String value=p.getProperty(key); check(value!=null&&!value.isBlank(),"Every private P22 setting is mandatory"); return value; }
        @Override public String toString() { return "OwnedMainSettings[redacted]"; }
    }
    private static void privateFile(Path path) throws Exception {
        check(path.isAbsolute() && path.normalize().equals(path) && Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS), "Exact regular private file is required");
        for (Path p=path;p!=null;p=p.getParent()) check(!Files.isSymbolicLink(p),"No private-file symlink ancestor is allowed");
        check(Files.getPosixFilePermissions(path).equals(Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE))
                && ((Number)Files.getAttribute(path,"unix:nlink")).intValue()==1 && System.getProperty("user.name").equals(Files.getOwner(path).getName()), "Current-owner single-link0600 file is required");
    }
    private void contract(String phase, Checked work) { observed(phase, () -> { work.run(); completedBody=true; }); }
    private static void observed(String phase, Checked work) { try { work.run(); } catch (AssertionError failed) { throw failed; } catch (Throwable failure) {
        String state="none";int line=-1;
        for(Throwable cause=failure;cause!=null;cause=cause.getCause()) {
            if(cause instanceof java.sql.SQLException sql && sql.getSQLState()!=null && sql.getSQLState().matches("[0-9A-Z]{5}")) state=sql.getSQLState();
            for(var frame:cause.getStackTrace()) if(frame.getClassName().equals(OwnedPostgresqlMainCoreIntegrationTest.class.getName()) && line<0) line=frame.getLineNumber();
        }
        throw new AssertionError("Owned P22 failed: "+phase+"; type="+failure.getClass().getName()+"; sqlstate="+state+"; testLine="+line);
    } }
    private static void check(boolean condition,String fixed) { if (!condition) throw new AssertionError(fixed); }
    private static void expectClosed(Checked work) { try { work.run(); } catch (IllegalStateException closed) { return; } catch (Exception failure) { throw new AssertionError("Unexpected analyzer close result"); } throw new AssertionError("Actual analyzer must close"); }
    private interface Checked { void run() throws Exception; }
    private static String sha(byte[] value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); } catch (Exception failure) { throw new AssertionError("SHA unavailable"); } }
    private void quiet() { for (String name:List.of("com.zaxxer.hikari","org.hibernate","org.springframework.jdbc","org.springframework.orm.jpa","org.springframework.context")) { var logger=(ch.qos.logback.classic.Logger)LoggerFactory.getLogger(name); levels.put(logger,logger.getLevel()); logger.setLevel(ch.qos.logback.classic.Level.OFF); } }

    private static final String[] CORE_FLAGS = {
        "spring.sql.init.mode=never", "spring.jmx.enabled=false", "pawbridge.database.writer.maximum-pool-size=1",
        "pawbridge.user-animal-activities.enabled=true", "pawbridge.user-animal-activities.animal-backend=module",
        "pawbridge.user-store-mypage.enabled=true", "pawbridge.user-store-mypage.backend=module",
        "pawbridge.animal-commands.enabled=true", "pawbridge.store-direct-orders.enabled=true",
        "pawbridge.auth.google-enabled=false", "pawbridge.auth.sessions-enabled=false", "pawbridge.chat.enabled=false",
        "pawbridge.jobs.cleanup-enabled=false", "pawbridge.user-maintenance.enabled=false", "pawbridge.community-maintenance.enabled=false",
        "pawbridge.favorite-delivery.enabled=false", "pawbridge.visits.enabled=false", "pawbridge.visits.collection-enabled=false",
        "pawbridge.animal-query.projection-schedule-enabled=false", "apms.batch.schedule-enabled=false", "apms-photo-archive.enabled=false",
        "tourapi.enabled=false", "tourapi.schedule-enabled=false", "lost-gallery-feed.enabled=false", "pawbridge.animal-lost-search.enabled=false",
        "pawbridge.store-carts.enabled=false", "pawbridge.store-catalog.enabled=false", "pawbridge.store-order-administration.enabled=false",
        "pawbridge.animal-discovery.enabled=false", "pawbridge.animal-recommendations.enabled=false", "pawbridge.animal-shelters.enabled=false",
        "pawbridge.animal-statistics.enabled=false", "pawbridge.animal-travel.enabled=false", "pawbridge.shelter-commands.enabled=false",
        "pawbridge.animal-reports.enabled=false", "pawbridge.store-wishlist.enabled=false", "pawbridge.shelter-membership.enabled=false",
        "pawbridge.home-videos.management-enabled=false"
    };
}
