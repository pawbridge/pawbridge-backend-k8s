package com.pawbridge.backend.compatibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pawbridge.backend.community.internal.post.infrastructure.JdbcAdminPostStatisticsQuery;
import com.pawbridge.backend.community.internal.post.web.AdminPostStatisticsExceptionHandler;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** No new fixture/ACL/seed stage. Actual pinned JPQL/JpaTM and the restricted reader see the same owned rows. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class PostgresqlAdminPostStatisticsCompatibilityTest {
    private static final long BASE = 99_940_000L;
    private static final long AUTHOR = 99_940_100L;
    private static final LocalDate START = LocalDate.of(2032, 2, 29), END = LocalDate.of(2032, 3, 3);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2033-03-05T00:00:00Z"), ZoneOffset.UTC);
    private static final String ORACLE_SHA = "1cd453e5fd3bd9f4cf40d3b0cf01854e9ce64356bed96190cf00c3f211461fd5";
    private final ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
    private final Set<Long> inserted = new LinkedHashSet<>();
    private F3PostgresqlFixture owner;
    private HikariDataSource reader;
    private JsonNode baseline;

    @BeforeAll void openTheExistingOwnedFixtureAndOneRestrictedReader() throws Exception {
        assertThat(ZoneId.systemDefault().getId()).isIn("UTC", "Asia/Seoul");
        owner = F3PostgresqlFixture.open(); verifyEvidence();
        assertThat(scalar("SELECT count(*) FROM pawbridge_community.posts WHERE post_id BETWEEN " + BASE + " AND " + (BASE + 99))).isEqualTo("0");
        HikariConfig config = new HikariConfig(); config.setJdbcUrl(owner.reads().jdbcUrl());
        config.setUsername(owner.reads().applicationUser()); config.setPassword(owner.reads().applicationPassword());
        config.setMaximumPoolSize(1); config.setMinimumIdle(0); config.setReadOnly(true);
        config.setConnectionTimeout(2_000); config.setValidationTimeout(1_000);
        config.addDataSourceProperty("connectTimeout", "3"); config.addDataSourceProperty("socketTimeout", "8");
        config.setConnectionInitSql("SET search_path TO public; SET statement_timeout=5000");
        config.setPoolName("admin-post-statistics-reader"); reader = new HikariDataSource(config);
        baseline = snapshot();
    }

    @AfterEach void removeOnlyAcknowledgedKeysAndRestoreTheWholeExistingFixture() throws Exception {
        cleanup(); returned();
        if (baseline != null) assertThat(snapshot().equals(baseline)).as("bounded snapshot unchanged").isTrue();
    }

    @AfterAll void closeTheReader() { if (reader != null) reader.close(); }

    @Test
    void givenSparseLeapDaysMicrosecondsAndTheJvmToday__whenReadingInEitherZone__thenMatchActualJpaGroupingBoundsAndSoftDeleteWithoutFillingGaps() throws Exception {
        requireEmptyPeriod();
        seed(1, "MISSING", LocalDateTime.parse("2032-02-28T00:00:00.000001"), false);
        seed(2, "MISSING", LocalDateTime.parse("2032-02-28T15:00:00"), false);
        seed(3, "PROTECTION", LocalDateTime.parse("2032-02-29T14:59:59.999999"), false);
        seed(4, "REPORT", LocalDateTime.parse("2032-02-29T15:00:00"), false);
        seed(5, "ADOPTION", LocalDateTime.parse("2032-03-03T14:59:59.999999"), false);
        seed(6, "REPORT", LocalDateTime.parse("2032-03-04T00:00:00"), false);
        seed(7, "COMMUNICATION", LocalDateTime.parse("2032-03-03T00:00:00.123456"), false);
        seed(8, "MISSING", LocalDateTime.parse("2032-02-29T12:00:00"), false);
        seed(9, "REPORT", LocalDateTime.parse("2032-02-29T12:00:00.999999"), true);
        var jdbc = new ObservingJdbc(); var candidate = candidate(jdbc, CLOCK);
        try (var source = source(CLOCK)) {
            JsonNode expected = wire(source.period(START, END));
            JsonNode actual = wire(candidate.period(START, END));
            assertEquivalent(expected, actual);
            assertThat(actual.size()).isEqualTo(5); assertThat(actual.get("previousDayCount").asLong()).isOne();
            assertThat(actual.get("daily")).hasSize(3);
            assertThat(actual.at("/daily/0/date").asText()).isEqualTo("2032-02-29");
            assertThat(actual.at("/daily/0/count").asLong()).isEqualTo(3);
            assertThat(actual.at("/daily/1/date").asText()).isEqualTo("2032-03-01");
            assertThat(actual.at("/daily/2/date").asText()).isEqualTo("2032-03-03");
            assertThat(actual.get("byBoardType")).hasSize(5);
            assertPeriodTransactions(source, jdbc);
            assertEquivalent(wire(source.period(START.plusMonths(1), END.plusMonths(1))),
                    wire(candidate.period(START.plusMonths(1), END.plusMonths(1))));
            assertThat(candidate.period(START.plusMonths(1), END.plusMonths(1)).daily()).isEmpty();
        }
        Instant before = Instant.now(); LocalDate jvmDay = LocalDate.now();
        LocalDateTime lower = jvmDay.atStartOfDay();
        // The raw source uses Timestamp.valueOf(LocalDateTime) + Hibernate's UTC Calendar too.
        // Preserve that JDBC encoding, rather than silently changing raw TIMESTAMP data to Instant semantics.
        seed(20, "REPORT", encoded(lower), false); seed(21, "REPORT", encoded(lower.plusDays(1).minusNanos(1_000)), false);
        seed(22, "REPORT", encoded(lower.plusDays(1)), false); seed(23, "REPORT", encoded(lower.plusHours(1)), true);
        try (var source = source(Clock.fixed(before, ZoneOffset.UTC))) {
            long expected = source.today(); long actual = candidate(new ObservingJdbc(), Clock.fixed(before, ZoneOffset.UTC)).today();
            assertThat(LocalDate.now()).as("Do not compare across a JVM midnight").isEqualTo(jvmDay);
            assertThat(actual).isEqualTo(expected);
            assertThat(source.queries()).singleElement().satisfies(query -> {
                assertThat(query.method()).isEqualTo("countByCreatedAtBetween");
                assertThat(query.start()).isEqualTo(lower); assertThat(query.end()).isEqualTo(lower.plusDays(1));
                assertThat(query.isolation()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED); assertThat(query.readOnly()).isTrue();
            });
            // Global earlier rows are intentionally included in the original count; no empty-population assumption.
            assertThat(actual).isGreaterThanOrEqualTo(2);
        }
    }

    @ParameterizedTest(name = "givenAnOutsideCommitBetweenTheTwoAggregates__whenReadingThePeriod__thenBothActualTransactionManagersKeepTheFirstRepeatableReadSnapshot[{index}]")
    @ValueSource(booleans = {true, false})
    void givenAnOutsideCommitBetweenTheTwoAggregates__whenReadingThePeriod__thenBothActualTransactionManagersKeepTheFirstRepeatableReadSnapshot(boolean original) throws Exception {
        requireEmptyPeriod(); seed(1, "MISSING", LocalDateTime.parse("2032-02-29T12:00:00"), false);
        Runnable outsideCommit = () -> {
            try {
                seed(2, "REPORT", LocalDateTime.parse("2032-02-29T13:00:00"), false);
                try (Connection connection = admin(); var statement = connection.prepareStatement("UPDATE pawbridge_community.posts SET deleted_at=TIMESTAMP '2032-03-05' WHERE post_id=?")) {
                    statement.setLong(1, BASE + 1); assertThat(statement.executeUpdate()).isOne();
                }
            } catch (Exception failure) { throw new IllegalStateException("Owned independent aggregate commit failed"); }
        };
        JsonNode during;
        if (original) try (var source = source(CLOCK)) {
            source.afterDaily(outsideCommit); during = wire(source.period(START, END));
            assertThat(source.queries()).extracting(F6LegacyAdminPostStatisticsOracle.Query::isolation)
                    .containsExactly(Connection.TRANSACTION_REPEATABLE_READ, Connection.TRANSACTION_REPEATABLE_READ);
        } else {
            var jdbc = new ObservingJdbc(); jdbc.afterDaily = outsideCommit;
            during = wire(candidate(jdbc, CLOCK).period(START, END));
            assertThat(jdbc.isolations).containsExactly(Connection.TRANSACTION_REPEATABLE_READ, Connection.TRANSACTION_REPEATABLE_READ);
        }
        assertThat(during.get("daily")).hasSize(1); assertThat(during.at("/daily/0/count").asLong()).isOne();
        assertThat(during.get("byBoardType")).hasSize(1); assertThat(during.at("/byBoardType/0/boardType").asText()).isEqualTo("MISSING");
        try (var source = source(CLOCK)) {
            JsonNode after = wire(source.period(START, END));
            assertThat(after.at("/byBoardType/0/boardType").asText()).isEqualTo("REPORT");
            assertEquivalent(after, wire(candidate(new ObservingJdbc(), CLOCK).period(START, END)));
        }
        returned();
    }

    @Test
    void givenTheBoardColumnRevoked__whenTheSecondQueryFailsThenPermissionIsRestored__thenReturnNoPartialDataAndRecoverWithoutAnyDatabaseDelta() throws Exception {
        requireEmptyPeriod(); seed(1, "REPORT", LocalDateTime.parse("2032-02-29T12:00:00.123456"), false);
        JsonNode before = snapshot(); var jdbc = new ObservingJdbc(); var candidate = candidate(jdbc, CLOCK);
        assertThat(scalar("SELECT has_column_privilege('pawbridge_f2_reader','pawbridge_community.posts','board_type','SELECT') AND NOT has_table_privilege('pawbridge_f2_reader','pawbridge_community.posts','SELECT')"))
                .as("Restore only a privilege that this exact column-only fixture originally owned").isEqualTo("t");
        try {
            execute("REVOKE SELECT(board_type) ON pawbridge_community.posts FROM pawbridge_f2_reader");
            Throwable failure = catchThrowable(() -> candidate.period(START, END));
            assertThat(failure).isInstanceOf(DataAccessException.class); assertThat(sqlState(failure)).isEqualTo("42501");
            assertThat(jdbc.completed).containsExactly("daily"); assertThat(jdbc.attempted).containsExactly("daily", "board");
            var response = new AdminPostStatisticsExceptionHandler().serverError((RuntimeException) failure);
            assertThat(response.getStatusCode().value()).isEqualTo(500);
            JsonNode error = wire(response.getBody()); assertThat(error.size()).isEqualTo(3);
            assertThat(error.get("message").asText()).isEqualTo("서버 에러!"); assertThat(error.get("data").isNull()).isTrue();
            assertThat(snapshot().equals(before)).as("bounded snapshot unchanged").isTrue(); returned();
        } finally {
            // Only the already verified hosted owner may restore precisely the one revoked column.
            verifyEvidence(); execute("GRANT SELECT(board_type) ON pawbridge_community.posts TO pawbridge_f2_reader");
            assertThat(scalar("SELECT has_column_privilege('pawbridge_f2_reader','pawbridge_community.posts','board_type','SELECT')")).isEqualTo("t");
        }
        try (var source = source(CLOCK)) { assertEquivalent(wire(source.period(START, END)), wire(candidate(new ObservingJdbc(), CLOCK).period(START, END))); }
        assertThat(snapshot().equals(before)).as("bounded snapshot unchanged").isTrue();
    }

    private JdbcAdminPostStatisticsQuery candidate(ObservingJdbc jdbc, Clock clock) {
        var manager = new JdbcTransactionManager(reader); manager.setEnforceReadOnly(true);
        var today = new TransactionTemplate(manager); today.setReadOnly(true); today.setTimeout(3);
        var period = new TransactionTemplate(manager); period.setReadOnly(true); period.setTimeout(3);
        period.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return new JdbcAdminPostStatisticsQuery(jdbc, today, period, clock);
    }
    private F6LegacyAdminPostStatisticsOracle source(Clock clock) { return new F6LegacyAdminPostStatisticsOracle(owner, clock, this::verifyEvidence); }
    private final class ObservingJdbc extends JdbcTemplate {
        final List<String> attempted = new ArrayList<>(), completed = new ArrayList<>();
        final List<Integer> isolations = new ArrayList<>(); Runnable afterDaily = () -> { };
        ObservingJdbc() { super(reader); setQueryTimeout(3); }
        @Override public <T> List<T> query(String sql, PreparedStatementSetter setter, RowMapper<T> rowMapper) throws DataAccessException {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
            String operation = sql.contains("GROUP BY board_type") ? "board" : sql.contains("GROUP BY CAST") ? "daily" : "today";
            attempted.add(operation);
            isolations.add(super.queryForObject("SHOW transaction_isolation", String.class).equals("repeatable read")
                    ? Connection.TRANSACTION_REPEATABLE_READ : Connection.TRANSACTION_READ_COMMITTED);
            List<T> result = super.query(sql, setter, rowMapper); completed.add(operation);
            if (operation.equals("daily")) afterDaily.run();
            return result;
        }
    }
    private void assertPeriodTransactions(F6LegacyAdminPostStatisticsOracle source, ObservingJdbc jdbc) {
        assertThat(source.queries()).extracting(F6LegacyAdminPostStatisticsOracle.Query::method).containsExactly("countDailyPosts", "countByBoardType");
        assertThat(source.queries()).allSatisfy(query -> { assertThat(query.isolation()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ); assertThat(query.readOnly()).isTrue(); });
        assertThat(jdbc.completed).containsExactly("daily", "board");
        assertThat(jdbc.isolations).containsExactly(Connection.TRANSACTION_REPEATABLE_READ, Connection.TRANSACTION_REPEATABLE_READ); returned();
    }
    private void assertEquivalent(JsonNode source, JsonNode candidate) {
        var expectedFields = (com.fasterxml.jackson.databind.node.ObjectNode) source.deepCopy(); expectedFields.remove("byBoardType");
        var actualFields = (com.fasterxml.jackson.databind.node.ObjectNode) candidate.deepCopy(); actualFields.remove("byBoardType");
        assertThat(actualFields).isEqualTo(expectedFields);
        var expected = new java.util.TreeMap<String, Long>(); source.get("byBoardType").forEach(row -> expected.put(row.get("boardType").asText(), row.get("count").asLong()));
        var actual = new java.util.TreeMap<String, Long>(); candidate.get("byBoardType").forEach(row -> actual.put(row.get("boardType").asText(), row.get("count").asLong()));
        assertThat(actual).isEqualTo(expected); assertThat(candidate.get("byBoardType").size()).isEqualTo(actual.size());
    }
    private JsonNode wire(Object value) throws Exception { return mapper.readTree(mapper.writeValueAsBytes(value)); }
    private static LocalDateTime encoded(LocalDateTime value) { return Timestamp.valueOf(value).toInstant().atOffset(ZoneOffset.UTC).toLocalDateTime(); }
    private void requireEmptyPeriod() throws Exception {
        assertThat(scalar("SELECT count(*) FROM pawbridge_community.posts WHERE created_at>=TIMESTAMP '2032-02-27' AND created_at<TIMESTAMP '2032-05-01'"))
                .as("Reserved aggregate dates must contain no earlier or foreign population").isEqualTo("0");
    }
    private void seed(int suffix, String board, LocalDateTime created, boolean deleted) throws Exception {
        long id = BASE + suffix;
        assertThat(scalar("SELECT count(*) FROM pawbridge_community.posts WHERE post_id=" + id)).isEqualTo("0");
        try (Connection connection = admin(); var statement = connection.prepareStatement("INSERT INTO pawbridge_community.posts(post_id,author_id,title,content,board_type,image_urls,created_at,updated_at,deleted_at) VALUES(?,?,'statistics fixture','statistics fixture',?,'[]',?,?,?)")) {
            statement.setLong(1, id); statement.setLong(2, AUTHOR); statement.setString(3, board);
            statement.setObject(4, created); statement.setObject(5, created); statement.setObject(6, deleted ? created.plusDays(1) : null);
            assertThat(statement.executeUpdate()).isOne(); inserted.add(id);
        }
    }
    private void cleanup() throws Exception {
        if (owner == null || inserted.isEmpty()) return;
        try (Connection connection = admin()) {
            try (var references = connection.prepareStatement("SELECT (SELECT count(*) FROM pawbridge_community.outbox_events WHERE aggregate_type='Post' AND aggregate_id=?)+(SELECT count(*) FROM pawbridge_community.post_search_documents WHERE post_id=?)")) {
                for (long id : inserted) { references.setString(1,Long.toString(id));references.setLong(2,id);try(var rows=references.executeQuery()){rows.next();assertThat(rows.getLong(1)).as("No foreign Outbox/search references to an owned statistics post").isZero();} }
            }
            var foreignKeys = connection.getMetaData().getExportedKeys(null, "pawbridge_community", "posts");
            while (foreignKeys.next()) {
                String table = '"' + foreignKeys.getString("FKTABLE_SCHEM").replace("\"","\"\"") + "\".\"" + foreignKeys.getString("FKTABLE_NAME").replace("\"","\"\"") + '"';
                String column = '"' + foreignKeys.getString("FKCOLUMN_NAME").replace("\"","\"\"") + '"';
                try (var check = connection.prepareStatement("SELECT count(*) FROM " + table + " WHERE " + column + "=?")) {
                    for (long id : inserted) { check.setLong(1,id); try(var rows=check.executeQuery()){rows.next();assertThat(rows.getLong(1)).as("Never cascade foreign rows while removing own posts").isZero();} }
                }
            }
            foreignKeys.close();
            try (var statement = connection.prepareStatement("DELETE FROM pawbridge_community.posts WHERE post_id=? AND author_id=?")) {
                for (long id : inserted) { statement.setLong(1,id); statement.setLong(2,AUTHOR); assertThat(statement.executeUpdate()).isOne(); }
            }
        }
        inserted.clear();
    }
    private JsonNode snapshot() throws Exception {
        var all = mapper.createObjectNode();
        // Snapshot every table already present, including public decoys/markers; this stage creates no schema/table.
        try (Connection connection = admin(); var statement = connection.createStatement(); var tables = statement.executeQuery(
                "SELECT schemaname,tablename FROM pg_tables WHERE schemaname='public' OR schemaname LIKE 'pawbridge\\_%' ESCAPE '\\' ORDER BY schemaname,tablename")) {
            while (tables.next()) {
                String schema=tables.getString(1), table=tables.getString(2);
                String qualified='"'+schema.replace("\"","\"\"")+"\".\""+table.replace("\"","\"\"")+'"';
                try (var read=connection.createStatement();var rows=read.executeQuery("SELECT COALESCE(jsonb_agg(to_jsonb(r) ORDER BY to_jsonb(r)::text),'[]'::jsonb)::text FROM "+qualified+" r")) {
                    rows.next();all.set(schema+"."+table,mapper.readTree(rows.getString(1)));
                }
            }
        }
        return all;
    }
    private void returned() { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); assertThat(TransactionSynchronizationManager.hasResource(reader)).isFalse(); if(reader!=null)assertThat(reader.getHikariPoolMXBean().getActiveConnections()).isZero(); }
    private void execute(String sql) throws Exception { try(Connection connection=admin();var statement=connection.createStatement()){statement.execute(sql);} }
    private String scalar(String sql) throws Exception { try(Connection connection=admin();var statement=connection.createStatement();var rows=statement.executeQuery(sql)){assertThat(rows.next()).isTrue();return rows.getString(1);} }
    private Connection admin() throws SQLException { verifyEvidence(); return owner.admin(); }
    private void verifyEvidence() {
        try {
            Path receipt=Path.of(System.getProperty("admin.post.statistics.oracle.receipt", "")).toAbsolutePath().normalize();
            assertThat(receipt.getFileName().toString()).isEqualTo("f6-admin-post-statistics-oracle-sha256.txt");
            assertThat(Files.isSymbolicLink(receipt)).isFalse(); assertThat(receipt.toRealPath()).isEqualTo(receipt);
            assertThat(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(receipt)))).isEqualTo(ORACLE_SHA);
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException("Pinned administrator post statistics evidence is unavailable"); }
    }
    private static String sqlState(Throwable failure) { var seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable,Boolean>());for(Throwable next=failure;next!=null&&seen.add(next);next=next.getCause())if(next instanceof SQLException sql&&sql.getSQLState()!=null)return sql.getSQLState();return null; }
}
