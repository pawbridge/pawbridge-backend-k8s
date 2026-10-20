package com.pawbridge.backend.compatibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pawbridge.backend.community.internal.post.application.PostPageReader;
import com.pawbridge.backend.community.internal.post.application.ReadAdminPosts;
import com.pawbridge.backend.community.internal.post.application.ReadPost;
import com.pawbridge.backend.community.internal.post.infrastructure.JdbcPostReader;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual F6 Repository/Pageable + F5 JpaTM; not a full PostService or HTTP execution. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class PostgresqlAdminPostReadCompatibilityTest {
    private static final long BASE = 99_950_000L, AUTHOR = 99_950_100L;
    private static final String NICKNAME = "admin-page-fixture";
    private static final String COLUMNS = "post_id,author_id,title,content,board_type,image_urls,deleted_at,created_at,updated_at";
    private static final String F6_RECEIPT_SHA = "3bd56301866ff614a4911eb3feb59bdde1638dbfa891f23167d4e38b5cfcb3fe";
    private final ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
    private final Set<Long> inserted = new LinkedHashSet<>();
    private F3PostgresqlFixture owner;
    private HikariDataSource reader;
    private F5LegacyPostOracle source;
    private JsonNode baseline;

    @BeforeAll void openExistingOwnedFixtureAndPinnedRepositoryWithOneRestrictedReader() throws Exception {
        assertThat(ZoneId.systemDefault().getId()).isEqualTo("UTC");
        owner = F3PostgresqlFixture.open(); verifyEvidence();
        assertThat(scalar("SELECT count(*) FROM pawbridge_community.posts WHERE post_id BETWEEN " + BASE + " AND " + (BASE + 99))).isEqualTo("0");
        verifyReaderColumns();
        HikariConfig config = new HikariConfig(); config.setJdbcUrl(owner.reads().jdbcUrl());
        config.setUsername(owner.reads().applicationUser()); config.setPassword(owner.reads().applicationPassword());
        config.setMaximumPoolSize(1); config.setMinimumIdle(0); config.setReadOnly(true);
        config.setConnectionTimeout(2_000); config.setValidationTimeout(1_000);
        config.addDataSourceProperty("connectTimeout", "3"); config.addDataSourceProperty("socketTimeout", "8");
        config.setConnectionInitSql("SET search_path TO public; SET statement_timeout=5000");
        config.setPoolName("admin-post-page-reader"); reader = new HikariDataSource(config);
        source = new F5LegacyPostOracle(owner, Clock.systemUTC());
        baseline = snapshot();
    }

    @AfterEach void removeOnlyAcknowledgedKeysAndProtectEveryExistingTable() throws Exception {
        cleanup(); returned(); verifyReaderColumns();
        if (baseline != null) assertThat(snapshot().equals(baseline)).as("whole existing fixture unchanged").isTrue();
    }

    @AfterAll void closeOriginalJpaContextAndReader() {
        try { if (source != null) source.close(); }
        finally { if (reader != null) reader.close(); }
    }

    @ParameterizedTest(name = "givenOwnedActiveAndDeletedPosts__whenPagingWithTheActualRepository__thenMatchRowsTotalsCountElisionAndNineFields[{index}]")
    @ValueSource(strings = {"first", "partial-last", "out-of-range"})
    void givenOwnedActiveAndDeletedPosts__whenPagingWithTheActualRepository__thenMatchRowsTotalsCountElisionAndNineFields(String scenario) throws Exception {
        seed(); long total = Long.parseLong(scalar("SELECT count(*) FROM pawbridge_community.posts WHERE deleted_at IS NULL"));
        assertThat(total).as("bounded synthetic fixture population").isBetween(5L, 10_000L);
        Pageable pageable;
        if (scenario.equals("partial-last")) {
            int size = 2; while (total % size == 0) size++;
            pageable = PageRequest.of((int) (total / size), size, Sort.by("postId"));
        } else if (scenario.equals("out-of-range")) {
            pageable = PageRequest.of((int) total + 1, 1, Sort.by("postId"));
        } else {
            // Owned timestamps are strictly later than the protected population; no artificial tie-break.
            pageable = PageRequest.of(0, 2, Sort.by(Sort.Order.desc("createdAt")));
        }
        JsonNode before = snapshot(); var observed = new ObservingJdbc();
        JsonNode expected = wire(source.page(pageable, NICKNAME));
        JsonNode actual = candidate(pageable, observed);
        assertThat(actual.equals(expected)).as("actual Repository Page and candidate wire agree").isTrue();
        assertThat(actual.get("totalElements").asLong()).isEqualTo(total);
        for (JsonNode row : actual.get("content")) assertThat(row.size()).isEqualTo(9);
        if (scenario.equals("first")) {
            assertThat(actual.at("/content/0/postId").asLong()).isEqualTo(BASE + 5);
            assertThat(actual.at("/content/1/postId").asLong()).isEqualTo(BASE + 4);
            assertThat(actual.at("/content/0/createdAt").asText()).endsWith(".123456");
        }
        List<String> operations = scenario.equals("partial-last") ? List.of("content") : List.of("content", "count");
        assertThat(source.pageOperations()).isEqualTo(operations); assertThat(observed.operations).isEqualTo(operations);
        sourceTransaction(); returned();
        assertThat(snapshot().equals(before)).as("read causes no database delta").isTrue();
    }

    @ParameterizedTest(name = "givenMappedStringNullableJsonAndUnknownSorts__whenReading__thenKeepActualPostgresOrderingOrFailureWithoutInventingTextCasts[{index}]")
    @ValueSource(strings = {"ignore-case", "nullable", "json", "unknown"})
    void givenMappedStringNullableJsonAndUnknownSorts__whenReading__thenKeepActualPostgresOrderingOrFailureWithoutInventingTextCasts(String scenario) throws Exception {
        seed(); Sort sort = switch (scenario) {
            case "ignore-case" -> Sort.by(Sort.Order.asc("authorId"), Sort.Order.asc("title").ignoreCase(), Sort.Order.desc("postId"));
            case "nullable" -> Sort.by(Sort.Order.asc("deletedAt"), Sort.Order.desc("postId"));
            case "json" -> Sort.by("imageUrls");
            default -> Sort.by("authorNickname");
        };
        Pageable pageable = PageRequest.of(0, 3, sort); JsonNode before = snapshot(); var observed = new ObservingJdbc();
        if (scenario.equals("json") || scenario.equals("unknown")) {
            Throwable original = catchThrowable(() -> source.page(pageable, NICKNAME));
            Throwable candidate = catchThrowable(() -> candidate(pageable, observed));
            assertThat(original instanceof RuntimeException).isTrue(); assertThat(candidate instanceof RuntimeException).isTrue();
            if (scenario.equals("json")) {
                assertThat(sqlState(original)).isEqualTo("42883"); assertThat(sqlState(candidate)).isEqualTo("42883");
                assertThat(source.pageOperations()).containsExactly("content"); assertThat(observed.operations).containsExactly("content");
            } else {
                assertThat(source.pageOperations()).isEmpty(); assertThat(observed.operations).isEmpty();
            }
        } else {
            assertThat(candidate(pageable, observed).equals(wire(source.page(pageable, NICKNAME))))
                    .as("native database string/null ordering matches").isTrue();
        }
        sourceTransaction(); returned();
        assertThat(snapshot().equals(before)).as("sort success/failure causes no database delta").isTrue();
    }

    @ParameterizedTest(name = "givenExactlyTheExistingReaderColumnsRevoked__whenContentOrCountFails__thenBothManagersRaise42501WithoutPartialRowsAndRecover[{index}]")
    @CsvSource({"true,content", "false,content", "true,count", "false,count"})
    void givenExactlyTheExistingReaderColumnsRevoked__whenContentOrCountFails__thenBothManagersRaise42501WithoutPartialRowsAndRecover(boolean original, String phase) throws Exception {
        seed(); Pageable pageable = PageRequest.of(0, 1, Sort.by("postId"));
        JsonNode before = snapshot(); var observed = new ObservingJdbc(); verifyReaderColumns();
        Runnable denyCount = () -> { try { permission("REVOKE", COLUMNS); } catch (Exception failure) { throw new IllegalStateException("Owned reader revocation failed"); } };
        try {
            if (phase.equals("content")) permission("REVOKE", "title");
            else if (original) source.beforePageCount(denyCount);
            else observed.afterContent = denyCount;
            Throwable failure = catchThrowable(() -> { if (original) source.page(pageable, NICKNAME); else candidate(pageable, observed); });
            assertThat(failure instanceof RuntimeException).isTrue(); assertThat(sqlState(failure)).isEqualTo("42501");
            List<String> operations = phase.equals("content") ? List.of("content") : List.of("content", "count");
            assertThat(original ? source.pageOperations() : observed.operations).isEqualTo(operations);
            returned(); assertThat(snapshot().equals(before)).as("failed read causes no database delta").isTrue();
        } finally {
            // Identity/immutable source evidence only: a revoked strict privilege must not prevent restoration.
            permission("GRANT", phase.equals("content") ? "title" : COLUMNS); verifyReaderColumns();
        }
        var recovered = new ObservingJdbc();
        assertThat(candidate(pageable, recovered).equals(wire(source.page(pageable, NICKNAME))))
                .as("both paths recover after only the exact previous grant is restored").isTrue();
        sourceTransaction(); returned();
    }

    private JsonNode candidate(Pageable pageable, ObservingJdbc jdbc) throws Exception {
        var manager = new JdbcTransactionManager(reader); manager.setEnforceReadOnly(true);
        var readOnly = new TransactionTemplate(manager); readOnly.setReadOnly(true); readOnly.setTimeout(3);
        var posts = new JdbcPostReader(jdbc, readOnly, mapper);
        var details = new ReadPost(posts, id -> { returned(); return Optional.of(NICKNAME); });
        var request = new PostPageReader.Request(pageable.getPageNumber(), pageable.getPageSize(), pageable.getSort().stream()
                .map(order -> new PostPageReader.Order(order.getProperty(), order.isAscending(), order.isIgnoreCase())).toList());
        var page = new ReadAdminPosts(posts, details).read(request);
        return wire(new PageImpl<>(page.content(), pageable, page.total()));
    }

    private final class ObservingJdbc extends JdbcTemplate {
        final List<String> operations = new ArrayList<>(); Runnable afterContent = () -> { };
        ObservingJdbc() { super(reader); setQueryTimeout(3); }
        @Override public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
            if (!sql.contains("FROM pawbridge_community.posts")) return super.query(sql, mapper, args);
            transaction(); operations.add("content"); List<T> result = super.query(sql, mapper, args); afterContent.run(); return result;
        }
        @Override public <T> T queryForObject(String sql, Class<T> type) {
            if (sql.contains("FROM pawbridge_community.posts")) { transaction(); operations.add("count"); }
            return super.queryForObject(sql, type);
        }
        private void transaction() {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
            assertThat(super.queryForObject("SHOW transaction_isolation", String.class)).isEqualTo("read committed");
        }
    }

    private void sourceTransaction() {
        assertThat(source.pageTransaction().role()).isEqualTo("pawbridge_f2_reader");
        assertThat(source.pageTransaction().readOnly()).isTrue();
        assertThat(source.pageTransaction().isolation()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
        assertThat(source.pageResourceBound()).isFalse();
    }
    private void returned() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        if (reader != null) { assertThat(TransactionSynchronizationManager.hasResource(reader)).isFalse(); assertThat(reader.getHikariPoolMXBean().getActiveConnections()).isZero(); }
        if (source != null) assertThat(source.pageResourceBound()).isFalse();
    }
    private JsonNode wire(Object value) throws Exception { return mapper.readTree(mapper.writeValueAsBytes(value)); }
    private void seed() throws Exception {
        LocalDateTime start;
        try (Connection connection = admin(); var statement = connection.createStatement(); var result = statement.executeQuery(
                "SELECT COALESCE(max(created_at),TIMESTAMP '2035-01-01') + INTERVAL '1 day' FROM pawbridge_community.posts")) {
            assertThat(result.next()).isTrue(); start = result.getTimestamp(1).toLocalDateTime().withNano(123_456_000);
        }
        String[] titles = {"Z", "a", "A", "b", "a", "deleted"};
        String[] images = {null, "null", "[null,\"\\uD55C\\uAE00\"]", "[\"second\",\"first\",\"second\"]", "[]", "[]"};
        for (int index = 0; index < titles.length; index++) {
            long id = BASE + index + 1; assertThat(scalar("SELECT count(*) FROM pawbridge_community.posts WHERE post_id=" + id)).isEqualTo("0");
            try (Connection connection = admin(); var statement = connection.prepareStatement(
                    "INSERT INTO pawbridge_community.posts(post_id,author_id,title,content,board_type,image_urls,created_at,updated_at,deleted_at) VALUES(?,?,?,' page body ','REPORT',CAST(? AS JSON),?,?,?)")) {
                statement.setLong(1,id); statement.setLong(2,AUTHOR); statement.setString(3,titles[index]); statement.setString(4,images[index]);
                statement.setObject(5,start.plusSeconds(index)); statement.setObject(6,start.plusSeconds(index + 1)); statement.setObject(7,index == 5 ? start.plusDays(1) : null);
                assertThat(statement.executeUpdate()).isOne(); inserted.add(id);
            }
        }
    }
    private void cleanup() throws Exception {
        if (owner == null || inserted.isEmpty()) return;
        try (Connection connection = admin()) {
            try (var check = connection.prepareStatement("SELECT (SELECT count(*) FROM pawbridge_community.outbox_events WHERE aggregate_type='Post' AND aggregate_id=?)+(SELECT count(*) FROM pawbridge_community.post_search_documents WHERE post_id=?)")) {
                for (long id : inserted) { check.setString(1,Long.toString(id)); check.setLong(2,id); try(var rows=check.executeQuery()){rows.next();assertThat(rows.getLong(1)).as("No external outbox/search references").isZero();} }
            }
            try (var keys = connection.getMetaData().getExportedKeys(null,"pawbridge_community","posts")) {
                while(keys.next()) {
                    String table = quoted(keys.getString("FKTABLE_SCHEM")) + "." + quoted(keys.getString("FKTABLE_NAME")); String column = quoted(keys.getString("FKCOLUMN_NAME"));
                    try(var check=connection.prepareStatement("SELECT count(*) FROM "+table+" WHERE "+column+"=?")) {
                        for(long id:inserted){check.setLong(1,id);try(var rows=check.executeQuery()){rows.next();assertThat(rows.getLong(1)).as("Never cascade external rows").isZero();}}
                    }
                }
            }
            try(var statement=connection.prepareStatement("DELETE FROM pawbridge_community.posts WHERE post_id=? AND author_id=?")) {
                for(long id:inserted){statement.setLong(1,id);statement.setLong(2,AUTHOR);assertThat(statement.executeUpdate()).isOne();}
            }
        }
        inserted.clear();
    }
    private JsonNode snapshot() throws Exception {
        var result=mapper.createObjectNode();
        try(Connection connection=admin();var statement=connection.createStatement();var tables=statement.executeQuery(
                "SELECT schemaname,tablename FROM pg_tables WHERE schemaname='public' OR schemaname LIKE 'pawbridge\\_%' ESCAPE '\\' ORDER BY schemaname,tablename")) {
            while(tables.next()) {
                String schema=tables.getString(1),table=tables.getString(2);
                try(var read=connection.createStatement();var rows=read.executeQuery("SELECT COALESCE(jsonb_agg(to_jsonb(r) ORDER BY to_jsonb(r)::text),'[]'::jsonb)::text FROM "+quoted(schema)+"."+quoted(table)+" r")) {
                    rows.next();result.set(schema+"."+table,mapper.readTree(rows.getString(1)));
                }
            }
        }
        return result;
    }
    private static String quoted(String value) { return '"' + value.replace("\"","\"\"") + '"'; }
    private void permission(String action,String columns) throws Exception {
        assertThat(action).isIn("GRANT","REVOKE"); assertThat(columns).isIn("title",COLUMNS);
        try(Connection connection=admin();var statement=connection.createStatement()) {
            statement.execute("SET lock_timeout='2s'; SET statement_timeout='5s'");
            statement.execute(action+" SELECT("+columns+") ON pawbridge_community.posts "+(action.equals("GRANT")?"TO":"FROM")+" pawbridge_f2_reader");
        }
    }
    private void verifyReaderColumns() throws Exception {
        assertThat(scalar("SELECT NOT has_table_privilege('pawbridge_f2_reader','pawbridge_community.posts','SELECT') AND count(*)=9 AND bool_and(has_column_privilege('pawbridge_f2_reader','pawbridge_community.posts',column_name,'SELECT')) FROM information_schema.columns WHERE table_schema='pawbridge_community' AND table_name='posts'")).isEqualTo("t");
    }
    private Connection admin() throws SQLException { verifyEvidence(); return owner.admin(); }
    private String scalar(String sql) throws Exception { try(Connection connection=admin();var statement=connection.createStatement();var result=statement.executeQuery(sql)){assertThat(result.next()).isTrue();return result.getString(1);} }
    private void verifyEvidence() {
        try {
            Path receipt=Path.of(System.getProperty("admin.post.read.oracle.receipt", "")).toAbsolutePath().normalize();
            assertThat(receipt.getFileName().toString()).isEqualTo("f6-oracle-sha256.txt"); assertThat(Files.isSymbolicLink(receipt)).isFalse(); assertThat(receipt.toRealPath()).isEqualTo(receipt);
            assertThat(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(receipt)))).isEqualTo(F6_RECEIPT_SHA);
            try (Connection connection=owner.admin();var statement=connection.createStatement();var marker=statement.executeQuery("SELECT value,owner FROM pawbridge_f5_fixture.marker")) {
                assertThat(marker.next()).isTrue();assertThat(marker.getString(1)).isEqualTo("pawbridge-f5-ci-only-v1");
                assertThat(marker.getString(2)).isEqualTo(System.getenv("GITHUB_RUN_ID")+"-"+System.getenv("GITHUB_RUN_ATTEMPT"));assertThat(marker.next()).isFalse();
            }
        } catch(java.io.IOException|java.security.NoSuchAlgorithmException|SQLException failure){throw new IllegalStateException("Pinned F6 repository evidence is unavailable");}
    }
    private static String sqlState(Throwable failure) {
        Set<Throwable> seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for(Throwable next=failure;next!=null&&seen.size()<32&&seen.add(next);next=next.getCause()) if(next instanceof SQLException sql&&sql.getSQLState()!=null)return sql.getSQLState();
        return null;
    }
}
