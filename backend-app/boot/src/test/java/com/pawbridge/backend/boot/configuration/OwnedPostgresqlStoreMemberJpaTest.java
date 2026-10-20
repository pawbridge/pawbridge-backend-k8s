package com.pawbridge.backend.boot.configuration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.pawbridge.backend.store.api.StoreMemberReads;
import com.pawbridge.backend.store.api.StoreMemberReadUnavailableException;
import com.pawbridge.backend.store.internal.memberread.infrastructure.JpaStoreMemberReads;
import com.pawbridge.storeservice.domain.mypage.dto.OrderResponse;
import com.pawbridge.storeservice.domain.mypage.dto.WishlistResponse;
import com.pawbridge.storeservice.domain.mypage.repository.MyOrderRepository;
import com.pawbridge.storeservice.domain.mypage.repository.MyWishlistRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.hibernate.resource.jdbc.spi.StatementInspector;
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
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.mockito.Mockito.*;

/** Reads one immutable owned fixture through original and mechanically relocated JPA models/repositories. */
@Tag("owned-postgresql")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(20)
class OwnedPostgresqlStoreMemberJpaTest {
    private static final long MEMBER = 900001L;
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final String[] LEGACY_TYPES = {
            "com.pawbridge.storeservice.common.entity.BaseEntity",
            "com.pawbridge.storeservice.domain.product.entity.Wishlist",
            "com.pawbridge.storeservice.domain.product.entity.ProductSKU",
            "com.pawbridge.storeservice.domain.product.entity.Product",
            "com.pawbridge.storeservice.domain.product.entity.Category",
            "com.pawbridge.storeservice.domain.product.entity.SKUValue",
            "com.pawbridge.storeservice.domain.product.entity.OptionValue",
            "com.pawbridge.storeservice.domain.product.entity.OptionGroup",
            "com.pawbridge.storeservice.domain.order.entity.Order",
            "com.pawbridge.storeservice.domain.order.entity.OrderItem"};
    private static final List<String> TABLES = List.of("categories", "option_groups", "option_values", "products",
            "product_skus", "sku_values", "wishlists", "orders", "order_items");
    private HikariDataSource pool;
    private JdbcTemplate jdbc;
    private LocalContainerEntityManagerFactoryBean legacyFactory;
    private LocalContainerEntityManagerFactoryBean candidateFactory;
    private EntityManagerFactory legacyEmf;
    private EntityManagerFactory candidateEmf;
    private TransactionTemplate legacyReads;
    private TransactionTemplate candidateReads;
    private MyWishlistRepository legacyWishlists;
    private MyOrderRepository legacyOrders;
    private StoreMemberReads candidate;
    private final StoreMemberReads cart = mock(StoreMemberReads.class);
    private final SqlObservation legacySql = new SqlObservation();
    private final SqlObservation candidateSql = new SqlObservation();
    private Map<String, Fingerprint> baseline;

    @BeforeAll void openVerifiedReadOnlyFixture() {
        observed("owned identity and JPA assembly", () -> {
            Settings settings = Settings.load();
            var initial = new DriverManagerDataSource(settings.jdbcUrl(), settings.username(), settings.password());
            Properties network = new Properties(); network.setProperty("connectTimeout", "3"); network.setProperty("socketTimeout", "5");
            initial.setConnectionProperties(network);
            verifyServer(new JdbcTemplate(initial), settings);
            var config = new HikariConfig();
            config.setJdbcUrl(settings.jdbcUrl()); config.setUsername(settings.username()); config.setPassword(settings.password());
            config.setPoolName("owned-store-member-reader"); config.setMaximumPoolSize(1); config.setMinimumIdle(0);
            config.setConnectionTimeout(2000); config.setValidationTimeout(1000); config.setInitializationFailTimeout(-1);
            config.setReadOnly(true); config.addDataSourceProperty("connectTimeout", "3"); config.addDataSourceProperty("socketTimeout", "5");
            pool = new HikariDataSource(config); jdbc = new JdbcTemplate(pool); jdbc.setQueryTimeout(5);
            legacyFactory = StoreMemberJpaConfiguration.newFactory(pool, "owned-store-legacy-reader", LEGACY_TYPES);
            legacyFactory.getJpaPropertyMap().put("hibernate.session_factory.statement_inspector", legacySql);
            legacyFactory.afterPropertiesSet(); legacyEmf = legacyFactory.getObject();
            candidateFactory = StoreMemberJpaConfiguration.newFactory(pool, "owned-store-candidate-reader", StoreMemberJpaConfiguration.MANAGED_TYPES);
            candidateFactory.getJpaPropertyMap().put("hibernate.session_factory.statement_inspector", candidateSql);
            candidateFactory.afterPropertiesSet(); candidateEmf = candidateFactory.getObject();
            check(legacyEmf != null && candidateEmf != null, "Both explicitly selected factories must initialize");
            legacyReads = reads(legacyEmf); candidateReads = reads(candidateEmf);
            var repositories = new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(legacyEmf));
            legacyWishlists = repositories.getRepository(MyWishlistRepository.class);
            legacyOrders = repositories.getRepository(MyOrderRepository.class);
            candidate = new JpaStoreMemberReads(SharedEntityManagerCreator.createSharedEntityManager(candidateEmf), candidateReads, cart);
            baseline = fingerprint();
            check(jdbc.queryForObject("SELECT count(*) FROM pawbridge_store.wishlists WHERE user_id=?", Long.class, MEMBER) == 3,
                    "The preseeded fixture must contain three owned wishlist parents");
            check(jdbc.queryForObject("SELECT count(*) FROM pawbridge_store.orders WHERE user_id=?", Long.class, MEMBER) == 3,
                    "The preseeded fixture must contain three owned order parents");
            released();
        });
    }

    @BeforeEach void resetObservations() {
        legacySql.reset(); candidateSql.reset();
        observed("unchanged baseline", () -> check(fingerprint().equals(baseline), "The nine owned tables must match their baseline"));
    }

    @AfterEach void requireNoMutationOrLeakedConnection() {
        observed("read-only result and connection return", () -> {
            check(fingerprint().equals(baseline), "JPA comparison must leave all nine tables unchanged");
            check(!legacySql.limitedCollection.get() && !candidateSql.limitedCollection.get(),
                    "Collection fetch SQL must not receive database LIMIT or OFFSET");
            verifyNoInteractions(cart);
            released();
        });
    }

    @AfterAll void closeFactoriesBeforeTheSharedPool() {
        boolean failed = false;
        try { if (candidateFactory != null) candidateFactory.destroy(); } catch (RuntimeException failure) { failed = true; }
        try { if (legacyFactory != null) legacyFactory.destroy(); } catch (RuntimeException failure) { failed = true; }
        if (candidateEmf != null) check(!candidateEmf.isOpen(), "The candidate factory must close");
        if (legacyEmf != null) check(!legacyEmf.isOpen(), "The original factory must close");
        if (pool != null) { pool.close(); check(pool.isClosed(), "The single reader pool must close"); }
        check(!failed, "Every explicitly owned factory must be destroyed");
    }

    static Stream<Arguments> wishlistSorts() {
        return Stream.of(
                Arguments.of("skuPrice", false), Arguments.of("sku_price", false),
                Arguments.of("sku.price", false), Arguments.of("sku.product.category.parent.name", false),
                Arguments.of("sku.skuValues.optionValue.name", false),
                Arguments.of("sku_skuValues_optionValue_name", false),
                Arguments.of("sku.product.name", true), Arguments.of("sku.product.status", true),
                Arguments.of("createdAt", false));
    }
    @ParameterizedTest(name = "wishlistSorts[{index}]") @MethodSource("wishlistSorts")
    void givenOriginalWishlistPropertyPaths_whenReading_thenContentPageSortAndWireMatch(String path, boolean ignoreCase) {
        observed("wishlist JPA sort parity", () -> compare(true, MEMBER, request(0, 2, path, ignoreCase)));
    }

    static Stream<Arguments> orderSorts() {
        return Stream.of(
                Arguments.of("orderItems.price", false), Arguments.of("orderItems_price", false),
                Arguments.of("orderItems.productSKU.price", false), Arguments.of("orderItems_productSKU_price", false),
                Arguments.of("orderItemsProductSKUPrice", false), Arguments.of("totalAmount", false),
                Arguments.of("status", true), Arguments.of("deliveryAddress", true), Arguments.of("createdAt", false));
    }
    @ParameterizedTest(name = "orderSorts[{index}]") @MethodSource("orderSorts")
    void givenOriginalOrderPropertyPaths_whenReading_thenSnapshotsPageSortAndWireMatch(String path, boolean ignoreCase) {
        observed("order JPA sort parity", () -> compare(false, MEMBER, request(0, 2, path, ignoreCase)));
    }

    @ParameterizedTest(name = "pageBoundaries[{index}]")
    @CsvSource({"0,2", "1,2", "2,2", "0,8"})
    void givenSkewedChildCountsAndPageBoundaries_whenReading_thenOriginalPageMetadataAndEmptyTailMatch(int page, int size) {
        observed("JPA page boundary parity", () -> {
            compare(true, MEMBER, request(page, size, "createdAt", false));
            compare(false, MEMBER, request(page, size, "createdAt", false));
        });
    }

    @ParameterizedTest(name = "memberIsolation[{index}]") @ValueSource(longs = {900002L, 900099L})
    void givenAnotherMemberOrAnEmptyMember_whenReading_thenOnlyThatMembersRowsAreReturned(long member) {
        observed("member predicate parity", () -> {
            var request = request(0, 8, "createdAt", false);
            compare(true, member, request); compare(false, member, request);
            long expected = member == 900002L ? 1 : 0;
            check(candidate.wishlists(member, request).totalElements() == expected
                    && candidate.orders(member, request).totalElements() == expected, "The exact member predicate must be preserved");
        });
    }

    @Test void givenZeroOneThreeChildrenAndChangedLiveSkuPrices_whenMapping_thenOriginalCollectionsAndPurchaseSnapshotsArePreserved() {
        observed("collection and purchase snapshot mapping", () -> {
            var request = request(0, 8, "createdAt", false);
            compare(true, MEMBER, request); compare(false, MEMBER, request);
            var wishes = candidate.wishlists(MEMBER, request);
            check(wishes.content().stream().map(row -> row.options().size()).toList().equals(List.of(0, 1, 3)),
                    "Collection-fetch pagination must preserve zero, one and three option rows");
            var orders = candidate.orders(MEMBER, request);
            check(orders.content().stream().map(row -> row.items().size()).toList().equals(List.of(0, 1, 3)),
                    "Order collection mapping must preserve zero, one and three purchased items");
            var item = orders.content().get(1).items().getFirst();
            check(item.price() == 777L && item.productName().equals("old product Z") && item.skuCode().equals("OLD-Z"),
                    "Order items must retain purchase snapshots rather than live SKU fields");
            check(legacySql.collections.get() > 0 && candidateSql.collections.get() > 0,
                    "Both original and candidate collection fetch SQL must actually be observed");
        });
    }

    @Test void givenUnboundedOrUnapprovedSortRequests_whenReading_thenRejectBeforeAnyJpaTransactionOrSql() {
        observed("bounded sort refusal before the reader transaction", () -> {
            TransactionTemplate guardedReads = reads(candidateEmf);
            AtomicInteger begins = new AtomicInteger();
            ((JpaTransactionManager) guardedReads.getTransactionManager()).addListener(
                    new org.springframework.transaction.TransactionExecutionListener() {
                        @Override public void beforeBegin(org.springframework.transaction.TransactionExecution transaction) {
                            begins.incrementAndGet();
                        }
                    });
            var guarded = new JpaStoreMemberReads(SharedEntityManagerCreator.createSharedEntityManager(candidateEmf), guardedReads, cart);
            var tooMany = new StoreMemberReads.Request(0, 2, java.util.Collections.nCopies(5,
                    new StoreMemberReads.Order("createdAt", true, false)));
            String recursive = "sku.product.category." + "parent.".repeat(64) + "name";
            for (boolean wishlist : List.of(true, false)) {
                var inputs = List.of(request(0, 2, recursive, false),
                        request(0, 2, wishlist ? "orderItems.price" : "sku.price", false), tooMany);
                for (StoreMemberReads.Request input : inputs) {
                    RuntimeException rejected = null;
                    try { if (wishlist) guarded.wishlists(MEMBER, input); else guarded.orders(MEMBER, input); }
                    catch (IllegalArgumentException expected) { rejected = expected; }
                    check(rejected != null && !rejected.getMessage().contains(recursive),
                            "Only a fixed request refusal may escape for a disallowed sort");
                    check(begins.get() == 0 && candidateSql.statements.get() == 0,
                            "Rejected sort paths and excessive sort counts must not start a JpaTM transaction or execute SQL");
                    released();
                }
            }
            verifyNoInteractions(cart);
        });
    }

    @Test void givenAnOffsetBeyondIntegerRange_whenReading_thenBothRejectBeforeAnOrmSelect() {
        observed("original large-offset refusal", () -> {
            var request = request(Integer.MAX_VALUE, 2, "createdAt", false);
            Failure original = failure(() -> original(true, MEMBER, request));
            Failure relocated = failure(() -> candidate.wishlists(MEMBER, request));
            check(original != null && "org.springframework.dao.InvalidDataAccessApiUsageException".equals(original.top())
                    && original.equals(relocated), "Large offset failure families must match the original after its known API wrapper");
            check(legacySql.statements.get() == 0 && candidateSql.statements.get() == 0,
                    "Offsets above the original integer bound must fail before SQL");
        });
    }

    @Test void givenTheSharedSingleConnectionReader_whenStartingEachJpaTransaction_thenPostgresqlReportsReadOnlyRepeatableReadAndReturnsIt() {
        observed("real reader transaction attributes", () -> {
            List<Integer> backends = new ArrayList<>();
            for (TransactionTemplate read : List.of(legacyReads, candidateReads)) {
                read.executeWithoutResult(status -> {
                    check("repeatable read".equals(jdbc.queryForObject("SHOW transaction_isolation", String.class)),
                            "The actual PostgreSQL transaction must use repeatable read");
                    check("on".equals(jdbc.queryForObject("SHOW transaction_read_only", String.class)),
                            "The actual PostgreSQL transaction must be read-only");
                    check(TransactionSynchronizationManager.hasResource(pool), "JpaTM must bind the shared reader connection for JDBC");
                    backends.add(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                });
                released();
            }
            check(backends.getFirst().equals(backends.getLast()) && pool.getMaximumPoolSize() == 1,
                    "Both factories must reuse the same one-slot physical reader pool");
        });
    }

    private void compare(boolean wishlist, long member, StoreMemberReads.Request request) {
        Page<?> original = original(wishlist, member, request);
        StoreMemberReads.Page<?> relocated = wishlist ? candidate.wishlists(member, request) : candidate.orders(member, request);
        Page<?> page = new PageImpl<>(relocated.content(), pageable(relocated.number(), relocated.size(), relocated.sort()), relocated.totalElements());
        check(original.getNumber() == page.getNumber() && original.getSize() == page.getSize()
                && original.getTotalElements() == page.getTotalElements() && original.getTotalPages() == page.getTotalPages()
                && original.getSort().equals(page.getSort()), "Original page metadata and Sort must match");
        JsonNode originalWire = JSON.valueToTree(original), candidateWire = JSON.valueToTree(page);
        check(originalWire.equals(candidateWire), "Original response content and Page wire must match without numeric or order normalization");
        released();
    }
    private Page<?> original(boolean wishlist, long member, StoreMemberReads.Request request) {
        return legacyReads.execute(status -> wishlist
                ? legacyWishlists.findByUserId(member, pageable(request.page(), request.size(), request.sort())).map(WishlistResponse::from)
                : legacyOrders.findByUserId(member, pageable(request.page(), request.size(), request.sort())).map(OrderResponse::from));
    }
    private TransactionTemplate reads(EntityManagerFactory factory) {
        var manager = new JpaTransactionManager(factory); manager.setDataSource(pool);
        var read = new TransactionTemplate(manager); read.setReadOnly(true); read.setTimeout(5);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ); return read;
    }
    private static StoreMemberReads.Request request(int page, int size, String path, boolean ignoreCase) {
        return new StoreMemberReads.Request(page, size, List.of(new StoreMemberReads.Order(path, true, ignoreCase)));
    }
    private static PageRequest pageable(int page, int size, List<StoreMemberReads.Order> source) {
        Sort sort = Sort.by(source.stream().map(value -> {
            var order = new Sort.Order(value.ascending() ? Sort.Direction.ASC : Sort.Direction.DESC, value.property());
            return value.ignoreCase() ? order.ignoreCase() : order;
        }).toList());
        return PageRequest.of(page, size, sort);
    }
    private Map<String, Fingerprint> fingerprint() {
        Map<String, Fingerprint> state = new LinkedHashMap<>();
        for (String table : TABLES) {
            var value = jdbc.queryForMap("SELECT count(*) AS n,md5(COALESCE(string_agg(row_to_json(owned_row)::text,',' ORDER BY id),'')) AS fingerprint "
                    + "FROM pawbridge_store." + table + " owned_row");
            state.put(table, new Fingerprint(((Number) value.get("n")).longValue(), (String) value.get("fingerprint")));
        }
        return state;
    }
    private void released() {
        check(!TransactionSynchronizationManager.isActualTransactionActive() && !TransactionSynchronizationManager.hasResource(pool),
                "No reader transaction may escape the query and DTO mapping boundary");
        check(pool.getHikariPoolMXBean().getActiveConnections() == 0 && pool.getHikariPoolMXBean().getThreadsAwaitingConnection() == 0,
                "The reader must return its sole connection after each operation");
    }
    private static void verifyServer(JdbcTemplate jdbc, Settings settings) {
        jdbc.setQueryTimeout(5);
        var identity = jdbc.queryForMap("SELECT current_database() AS db,current_user AS actor,session_user AS session,"
                + "current_setting('server_version_num') AS version,(SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname=current_database()) AS owner");
        check(settings.database().equals(identity.get("db")) && settings.username().equals(identity.get("actor"))
                && settings.username().equals(identity.get("session")) && "170011".equals(identity.get("version"))
                && !settings.username().equals(identity.get("owner")), "Owned database, server and separate reader identity must match");
        var markers = jdbc.query("SELECT marker FROM public.pawbridge_owned_fixture", (row, index) -> row.getString(1));
        check(markers.size() == 1 && settings.marker().equals(markers.getFirst()), "The exact isolated fixture marker must match");
        Boolean safe = jdbc.queryForObject("SELECT NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole "
                + "AND NOT pg_has_role(current_user,(SELECT datdba FROM pg_database WHERE datname=current_database()),'MEMBER') "
                + "FROM pg_roles WHERE rolname=current_user", Boolean.class);
        check(Boolean.TRUE.equals(safe), "The reader must have no administrative role or owner inheritance");
        for (String table : TABLES) {
            Boolean limited = jdbc.queryForObject("SELECT has_table_privilege(current_user,?,'SELECT') "
                    + "AND NOT has_table_privilege(current_user,?,'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') "
                    + "AND NOT has_any_column_privilege(current_user,?,'INSERT,UPDATE,REFERENCES')", Boolean.class,
                    "pawbridge_store." + table, "pawbridge_store." + table, "pawbridge_store." + table);
            check(Boolean.TRUE.equals(limited), "The selected reader must have only SELECT on every fixture table");
        }
    }
    private static Failure failure(Checked action) {
        try { action.run(); return null; }
        catch (RuntimeException failure) {
            // Only the explicit Store API technology wrapper is transparent for this source-error comparison.
            Throwable contract = failure instanceof StoreMemberReadUnavailableException && failure.getCause() != null
                    ? failure.getCause() : failure;
            Throwable root = contract; while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            return new Failure(contract.getClass().getName(), root.getClass().getName());
        } catch (Exception failure) { throw new AssertionError("The observation must fail through its runtime error family"); }
    }
    private static void observed(String phase, Checked action) {
        try { action.run(); }
        catch (AssertionError failure) { throw failure; }
        catch (Throwable failure) { throw new AssertionError("Owned Store JPA observation failed: " + phase); }
    }
    private static void check(boolean condition, String fixedMessage) { if (!condition) throw new AssertionError(fixedMessage); }
    private interface Checked { void run() throws Exception; }
    private record Failure(String top, String root) { }
    private record Fingerprint(long count, String digest) { }
    private static final class SqlObservation implements StatementInspector {
        private final AtomicInteger statements = new AtomicInteger(), collections = new AtomicInteger();
        private final AtomicBoolean limitedCollection = new AtomicBoolean();
        @Override public String inspect(String sql) {
            statements.incrementAndGet();
            String normalized = sql.toLowerCase(Locale.ROOT);
            if (normalized.matches("(?s).*\\bjoin\\s+(?:pawbridge_store\\.)?(?:sku_values|order_items)\\b.*")) {
                collections.incrementAndGet();
                if (normalized.matches("(?s).*\\b(?:limit|offset)\\b.*") || normalized.matches("(?s).*\\bfetch\\s+(?:first|next)\\b.*"))
                    limitedCollection.set(true);
            }
            return sql;
        }
        void reset() { statements.set(0); collections.set(0); limitedCollection.set(false); }
    }
    private record Settings(String jdbcUrl, String database, String username, String password, String marker) {
        static Settings load() throws Exception {
            String path = System.getenv("P7_PG_SETTINGS");
            check(path != null && !path.isBlank(), "P7_PG_SETTINGS is mandatory; owned tests must not skip");
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
            var parentPermissions = Files.getPosixFilePermissions(file.getParent(), LinkOption.NOFOLLOW_LINKS);
            check(!parentPermissions.contains(PosixFilePermission.GROUP_WRITE) && !parentPermissions.contains(PosixFilePermission.OTHERS_WRITE),
                    "Settings parent must reject writes from other users");
            Properties values = new Properties();
            try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { values.load(input); }
            String database = required(values, "database"), url = required(values, "jdbcUrl");
            check(database.matches("pb_store_member_owned_[a-z0-9_]{8,32}")
                    && url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[1-9][0-9]{0,4}/" + database),
                    "Only the explicitly owned loopback database URL is allowed");
            check(Integer.parseInt(url.substring(url.lastIndexOf(':') + 1, url.lastIndexOf('/'))) <= 65535, "The explicit port must be valid");
            String username = required(values, "username"), marker = required(values, "marker");
            check(username.matches("[a-z][a-z0-9_]{1,62}") && marker.matches("[0-9a-f]{32}"), "Reader and fixture marker must be explicit");
            return new Settings(url, database, username, required(values, "password"), marker);
        }
        private static String required(Properties values, String key) {
            String value = values.getProperty(key); check(value != null && !value.isBlank(), "Every owned setting is required"); return value;
        }
        @Override public String toString() { return "OwnedStoreSettings[redacted]"; }
    }
}
