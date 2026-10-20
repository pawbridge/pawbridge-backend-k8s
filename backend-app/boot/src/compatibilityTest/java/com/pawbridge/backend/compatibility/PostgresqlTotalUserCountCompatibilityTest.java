package com.pawbridge.backend.compatibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.boot.security.PostTokenAuthenticator;
import com.pawbridge.backend.user.internal.statistics.application.TotalUserCountQuery;
import com.pawbridge.backend.user.internal.statistics.infrastructure.JdbcTotalUserCountQuery;
import com.pawbridge.userservice.entity.Role;
import com.pawbridge.userservice.entity.User;
import com.pawbridge.userservice.jwt.JwtProvider;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** F19 comparison: actual inherited JPA count and restricted candidate; no whole User service or Gateway execution. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation.class)
class PostgresqlTotalUserCountCompatibilityTest {
    private static final long BASE = 9_973_100L;
    private final Set<Long> ownedUsers = new LinkedHashSet<>(), ownedPending = new LinkedHashSet<>();
    private F3PostgresqlFixture owner;
    private HikariDataSource reader;
    private JdbcTemplate jdbc;
    private TotalUserCountQuery query;
    private F19LegacyTotalUserCountOracle original;
    private StatisticsJdbc statisticsJdbc;
    private TransactionTemplate statisticsTransaction;
    private static final java.time.ZoneId SEOUL = java.time.ZoneId.of("Asia/Seoul");
    private static final long SIGNUP_BASE = 9_985_300L;

    @BeforeAll void openOnlyTheOwnedFixtureAndOneReaderConnection() throws Exception {
        assertThat(System.getProperty("user.timezone")).isIn("UTC", "Asia/Seoul");
        owner = F3PostgresqlFixture.open(); verifyOwnership();
        var config = new HikariConfig(); config.setJdbcUrl(owner.reads().jdbcUrl());
        config.setUsername(owner.reads().applicationUser()); config.setPassword(owner.reads().applicationPassword());
        config.setReadOnly(true); config.setMaximumPoolSize(1); config.setMinimumIdle(0);
        config.setConnectionTimeout(2_000); config.setValidationTimeout(1_000); config.setPoolName("f19-total-user-reader");
        reader = new HikariDataSource(config); jdbc = new JdbcTemplate(reader); jdbc.setQueryTimeout(3);
        var manager = new JdbcTransactionManager(reader); manager.setEnforceReadOnly(true);
        var tx = new TransactionTemplate(manager); tx.setReadOnly(true); tx.setTimeout(3);
        query = new JdbcTotalUserCountQuery(jdbc, tx);
        statisticsTransaction = tx;
        statisticsJdbc = new StatisticsJdbc(reader); statisticsJdbc.setQueryTimeout(3);
        original = new F19LegacyTotalUserCountOracle(owner, this::verifyOwnership);
        returned();
    }

    @AfterEach void removeOnlySuccessfulOwnedInsertsInForeignKeyOrder() throws Exception {
        if (owner != null) {
            deleteIds("pawbridge_user.contact_deletions", "user_id", ownedPending);
            deleteIds("pawbridge_user.users", "user_id", ownedUsers);
        }
        ownedPending.clear(); ownedUsers.clear();
        if (reader != null) returned();
    }
    @AfterAll void closeOnlyOwnPoolsAndOracle() { try { if (original != null) original.close(); } finally { if (reader != null) reader.close(); } }

    @Test void givenPhysicalUsersOfDifferentRolesProvidersNullDatesAndPendingState__whenCounting__thenActualInheritedJpaAndRestrictedJdbcIncludeEveryOwnedRow() throws Exception {
        long baseline = original.count(); returned(); assertThat(query.count()).isEqualTo(baseline); returned();
        user(BASE + 1, "ROLE_USER", "GOOGLE"); user(BASE + 2, "ROLE_SHELTER", "NAVER"); user(BASE + 3, "ROLE_ADMIN", "LOCAL");
        String before = snapshot();
        assertThat(original.count()).isEqualTo(baseline + 3); returned();
        assertThat(query.count()).isEqualTo(baseline + 3); returned(); assertThat(snapshot()).isEqualTo(before);
        pending(BASE + 2); before = snapshot();
        assertThat(original.count()).isEqualTo(baseline + 3); returned();
        assertThat(query.count()).isEqualTo(baseline + 3); returned(); assertThat(snapshot()).isEqualTo(before);
    }

    @Test void givenUserIdSelectRevoked__whenCountingThenRestoring__thenTheCandidateFailsInsteadOfReturningZeroAndRecoversWithoutChangingRows() throws Exception {
        String before = snapshot(); long expected = original.count(); returned();
        execute("REVOKE SELECT(user_id) ON pawbridge_user.users FROM pawbridge_f2_reader");
        try {
            assertThatThrownBy(query::count).isInstanceOf(DataAccessException.class); returned();
            assertThat(snapshot()).isEqualTo(before);
        } finally { execute("GRANT SELECT(user_id) ON pawbridge_user.users TO pawbridge_f2_reader"); }
        assertThat(query.count()).isEqualTo(expected); returned(); assertThat(snapshot()).isEqualTo(before);
    }


    @Test @Order(Integer.MAX_VALUE)
    void givenTheOriginalIssuerAndOwnedFile__whenPreparingOnlyUsedJarPrincipals__thenPreserveAllOldKeysWithoutPrintingTokens() throws Exception {
        verifyOwnership(); String run = F3PostgresqlFixture.required("GITHUB_RUN_ID") + "-" + F3PostgresqlFixture.required("GITHUB_RUN_ATTEMPT");
        Path file = Path.of(F3PostgresqlFixture.required("F3_TEST_TOKENS_PATH")).toAbsolutePath().normalize();
        assertThat(file).isEqualTo(Path.of(F3PostgresqlFixture.required("RUNNER_TEMP")).toAbsolutePath().normalize().resolve("f3-tokens-" + run + ".json"));
        assertThat(Files.isSymbolicLink(file)).isFalse(); assertThat(Files.isRegularFile(file)).isTrue();
        assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        var mapper = new ObjectMapper();
        Map<String, String> tokens = new LinkedHashMap<>(mapper.readValue(Files.readString(file), new TypeReference<Map<String, String>>() { }));
        Set<String> before = Set.copyOf(tokens.keySet()); String secret = F3PostgresqlFixture.required("F3_TEST_JWT_SECRET");
        var issuer = new JwtProvider(secret, 600_000, 600_000);
        var authenticator = new PostTokenAuthenticator(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)));
        for (var e : Map.of("F19_ADMIN", 9_973_001L, "F19_USER", 9_973_002L).entrySet()) {
            String token = issuer.createAccessToken(tokenUser(e.getValue(), e.getKey().equals("F19_ADMIN") ? Role.ROLE_ADMIN : Role.ROLE_USER));
            assertThat(authenticator.authenticate("Bearer " + token)).isEqualTo(e.getValue()); tokens.put(e.getKey(), token);
        }
        assertThat(tokens.keySet()).containsAll(before).contains("F19_ADMIN", "F19_USER");
        Files.writeString(file, mapper.writeValueAsString(tokens)); Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        returned();
    }

    @Test @org.junit.jupiter.api.Tag("signup-statistics")
    void givenUtcKstMicrosecondBoundariesNullAndAGap__whenDailyQueriesRunInEitherZone__thenMatchOriginalJpqlWithoutFillingOrReordering() throws Exception {
        var start = java.time.LocalDate.of(2044, 3, 1);
        original.observeSignupQueries(ignored -> { });
        assertThat(original.daily(start, start.plusDays(3))).isEmpty();
        signupUser(SIGNUP_BASE + 1, start.atTime(14, 59, 59, 999999000));
        signupUser(SIGNUP_BASE + 2, start.atTime(15, 0));
        signupUser(SIGNUP_BASE + 3, start.plusDays(2).atTime(15, 0));
        signupUser(SIGNUP_BASE + 4, null);
        pending(SIGNUP_BASE + 2);
        String before = snapshot();
        original.observeSignupQueries(ignored -> { }); statisticsJdbc.reset(ignored -> { });
        var statistics = statistics(start);
        var expected = java.util.List.of(start + "=1", start.plusDays(1) + "=1", start.plusDays(3) + "=1");
        assertThat(days(original.daily(start, start.plusDays(3)))).isEqualTo(expected); returned();
        assertThat(candidateDays(statistics.daily(start, start.plusDays(3)))).isEqualTo(expected); returned();
        assertThat(original.daily(start.plusDays(3), start)).isEmpty();
        assertThat(statistics.daily(start.plusDays(3), start)).isEmpty();
        assertThat(original.daily(start.plusDays(2), start.plusDays(2))).isEmpty();
        assertThat(statistics.daily(start.plusDays(2), start.plusDays(2))).isEmpty();
        assertThat(original.signupQueries()).hasSize(3); assertThat(statisticsJdbc.queries).hasSize(3);
        assertReadOnlyCommitted(original.signupQueries()); assertReadOnlyCommitted(statisticsJdbc.queries);
        returned(); assertThat(snapshot()).isEqualTo(before);
    }

    @Test @org.junit.jupiter.api.Tag("signup-statistics")
    void givenACommitAfterTheFirstPeriodQuery__whenFourQueriesShareReadCommitted__thenMatchOriginalRangesZeroFillAndLaterVisibility() throws Exception {
        java.util.Map<String, java.util.List<String>> sourceResult = null;
        for (boolean source : new boolean[]{true, false}) {
            var today = java.time.LocalDate.now(SEOUL);
            long baselineToday = original.daily(today, today).stream().mapToLong(row -> row.count()).sum();
            String before = snapshot();
            java.util.function.IntConsumer insertAfterFirst = count -> {
                if (count == 1) {
                    try { signupUser(SIGNUP_BASE + 10, today.atTime(3, 0)); }
                    catch (SQLException failure) { throw new IllegalStateException("Owned signup observation insert failed"); }
                }
            };
            original.observeSignupQueries(insertAfterFirst); statisticsJdbc.reset(insertAfterFirst);
            try {
                java.util.Map<String, java.util.List<String>> result;
                java.util.List<F19LegacyTotalUserCountOracle.SignupRange> ranges;
                if (source) {
                    var rows = original.periods();
                    result = java.util.Map.of("today", days(rows.getToday()), "last7Days", days(rows.getLast7Days()),
                            "last30Days", days(rows.getLast30Days()), "thisMonth", days(rows.getThisMonth()));
                    ranges = original.signupQueries();
                } else {
                    var rows = statistics(today).periods();
                    result = java.util.Map.of("today", candidateDays(rows.today()), "last7Days", candidateDays(rows.last7Days()),
                            "last30Days", candidateDays(rows.last30Days()), "thisMonth", candidateDays(rows.thisMonth()));
                    ranges = statisticsJdbc.queries;
                }
                assertThat(java.time.LocalDate.now(SEOUL)).as("Refuse a source LocalDate.now midnight crossing").isEqualTo(today);
                returned(); assertThat(ranges).hasSize(4); assertReadOnlyCommitted(ranges);
                assertThat(ranges.stream().map(r -> r.start() + "/" + r.end()).toList()).containsExactly(
                        today + "/" + today, today.minusDays(6) + "/" + today,
                        today.minusDays(29) + "/" + today, today.withDayOfMonth(1) + "/" + today);
                assertThat(result.get("today")).containsExactly(today + "=" + baselineToday);
                assertThat(result.get("last7Days")).hasSize(7).contains(today + "=" + (baselineToday + 1));
                assertThat(result.get("last30Days")).hasSize(30).contains(today + "=" + (baselineToday + 1));
                assertThat(result.get("thisMonth")).hasSize(today.getDayOfMonth()).contains(today + "=" + (baselineToday + 1));
                if (source) sourceResult = result; else assertThat(result).isEqualTo(sourceResult);
            } finally {
                original.observeSignupQueries(ignored -> { }); statisticsJdbc.reset(ignored -> { });
                deleteIds("pawbridge_user.users", "user_id", ownedUsers); ownedUsers.clear();
            }
            assertThat(snapshot()).isEqualTo(before);
        }
    }

    @Test @org.junit.jupiter.api.Tag("signup-statistics")
    void givenCreatedAtSelectRevoked__whenDailyReadFailsThenRecovers__thenReturnConnectionAndKeepTheOwnedBaseline() throws Exception {
        var day = java.time.LocalDate.of(2044, 3, 1); var statistics = statistics(day);
        var expected = days(original.daily(day, day)); String before = snapshot();
        verifyOwnership(); execute("REVOKE SELECT(created_at) ON pawbridge_user.users FROM pawbridge_f2_reader");
        try {
            assertThatThrownBy(() -> statistics.daily(day, day)).isInstanceOf(DataAccessException.class);
            returned(); assertThat(snapshot()).isEqualTo(before);
        } finally {
            verifyOwnership(); execute("GRANT SELECT(created_at) ON pawbridge_user.users TO pawbridge_f2_reader");
        }
        assertThat(candidateDays(statistics.daily(day, day))).isEqualTo(expected);
        returned(); assertThat(snapshot()).isEqualTo(before);
    }

    private com.pawbridge.backend.user.internal.statistics.application.SignupStatisticsQuery statistics(java.time.LocalDate today) {
        return new com.pawbridge.backend.user.internal.statistics.infrastructure.JdbcSignupStatisticsQuery(
                statisticsJdbc, statisticsTransaction,
                java.time.Clock.fixed(today.atTime(12, 0).atZone(SEOUL).toInstant(), java.time.ZoneOffset.UTC));
    }
    private void signupUser(long id, java.time.LocalDateTime createdAt) throws SQLException {
        try (Connection c = admin(); var s = c.prepareStatement("SELECT count(*) FROM pawbridge_user.users WHERE user_id=?")) {
            s.setLong(1, id); try (var rows = s.executeQuery()) { assertThat(rows.next()).isTrue(); assertThat(rows.getLong(1)).isZero(); }
        }
        user(id, id % 2 == 0 ? "ROLE_ADMIN" : "ROLE_USER", id % 2 == 0 ? "LOCAL" : "GOOGLE");
        if (createdAt != null) try (Connection c = admin(); var s = c.prepareStatement("UPDATE pawbridge_user.users SET created_at=? WHERE user_id=?")) {
            s.setObject(1, createdAt); s.setLong(2, id); assertThat(s.executeUpdate()).isOne();
        }
    }
    private static java.util.List<String> days(java.util.List<com.pawbridge.userservice.dto.response.DailySignupStatsResponse> rows) {
        return rows.stream().map(row -> row.date() + "=" + row.count()).toList();
    }
    private static java.util.List<String> candidateDays(java.util.List<com.pawbridge.backend.user.internal.statistics.application.SignupStatisticsQuery.DailySignupStats> rows) {
        return rows.stream().map(row -> row.date() + "=" + row.count()).toList();
    }
    private static void assertReadOnlyCommitted(java.util.List<F19LegacyTotalUserCountOracle.SignupRange> ranges) {
        for (var range : ranges) {
            assertThat(range.isolation()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
            assertThat(range.readOnly()).isTrue();
        }
    }
    private static final class StatisticsJdbc extends JdbcTemplate {
        private final javax.sql.DataSource dataSource;
        private final java.util.List<F19LegacyTotalUserCountOracle.SignupRange> queries = new java.util.ArrayList<>();
        private java.util.function.IntConsumer afterQuery = ignored -> { };
        StatisticsJdbc(javax.sql.DataSource source) { super(source); dataSource = source; }
        void reset(java.util.function.IntConsumer hook) { queries.clear(); afterQuery = hook; }
        @Override public <T> java.util.List<T> query(String sql, org.springframework.jdbc.core.RowMapper<T> mapper, Object... args) {
            var result = super.query(sql, mapper, args);
            var connection = org.springframework.jdbc.datasource.DataSourceUtils.getConnection(dataSource);
            try {
                queries.add(new F19LegacyTotalUserCountOracle.SignupRange((java.time.LocalDate) args[0],
                        (java.time.LocalDate) args[1], connection.getTransactionIsolation(), connection.isReadOnly()));
            } catch (SQLException failure) { throw new IllegalStateException("Could not observe owned read transaction"); }
            finally { org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(connection, dataSource); }
            afterQuery.accept(queries.size());
            return result;
        }
    }

    private User tokenUser(long id, Role role) { return User.builder().userId(id).email("f19-token@fixture.invalid").name("F19 synthetic")
            .nickname("F19token").provider("GOOGLE").role(role).build(); }
    private void user(long id, String role, String provider) throws SQLException {
        try (Connection c = admin(); var statement = c.prepareStatement("INSERT INTO pawbridge_user.users(user_id,email,name,nickname,provider,role,created_at) VALUES(?,?,?,?,?,?,NULL)")) {
            statement.setLong(1, id); statement.setString(2, "f19-" + id + "@fixture.invalid"); statement.setString(3, "F19 synthetic");
            statement.setString(4, "F19user" + id); statement.setString(5, provider); statement.setString(6, role);
            statement.executeUpdate(); ownedUsers.add(id);
        }
    }
    private void pending(long id) throws SQLException {
        try (Connection c = admin(); var s = c.prepareStatement("INSERT INTO pawbridge_user.contact_deletions(user_id) VALUES(?)")) {
            s.setLong(1, id); s.executeUpdate(); ownedPending.add(id);
        }
    }
    private void deleteIds(String table, String column, Set<Long> ids) throws SQLException {
        if (!Set.of("pawbridge_user.contact_deletions.user_id", "pawbridge_user.users.user_id").contains(table + "." + column))
            throw new IllegalArgumentException("Only owned F19 key cleanup is supported");
        try (Connection c = admin(); var s = c.prepareStatement("DELETE FROM " + table + " WHERE " + column + "=?")) {
            for (long id : ids) { s.setLong(1, id); s.executeUpdate(); }
        }
    }
    private Connection admin() throws SQLException {
        Connection c = owner.admin();
        try (var s = c.createStatement(); var rows = s.executeQuery("SELECT 16,value,owner FROM pawbridge_f16_fixture.marker UNION ALL SELECT 17,value,owner FROM pawbridge_f17_fixture.marker ORDER BY 1")) {
            String run = F3PostgresqlFixture.required("GITHUB_RUN_ID") + "-" + F3PostgresqlFixture.required("GITHUB_RUN_ATTEMPT");
            for (int stage : new int[]{16, 17}) {
                assertThat(F3PostgresqlFixture.required("F" + stage + "_CI_OWNER")).isEqualTo(run);
                assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(stage);
                assertThat(rows.getString(2)).isEqualTo("pawbridge-f" + stage + "-ci-only-v1"); assertThat(rows.getString(3)).isEqualTo(run);
            }
            assertThat(rows.next()).isFalse();
        } catch (SQLException | RuntimeException | Error failure) { c.close(); throw failure; }
        return c;
    }
    private void verifyOwnership() { try (Connection ignored = admin()) { } catch (SQLException e) { throw new IllegalStateException(e); } }
    private void execute(String sql) throws SQLException { try (Connection c = admin(); var s = c.createStatement()) { s.execute(sql); } }
    private String snapshot() throws SQLException {
        String sql = "SELECT md5(json_build_object("
                + "'pending',(SELECT COALESCE(json_agg(d ORDER BY user_id),'[]') FROM pawbridge_user.contact_deletions d),"
                + "'members',(SELECT COALESCE(json_agg(m ORDER BY member_id),'[]') FROM pawbridge_community.private_note_members m),"
                + "'blocks',(SELECT COALESCE(json_agg(b ORDER BY member_id,blocked_id),'[]') FROM pawbridge_community.private_note_blocks b),"
                + "'visits',(SELECT COALESCE(json_agg(v ORDER BY visit_date),'[]') FROM pawbridge_community.daily_visit_count v),"
                + "'videos',(SELECT COALESCE(json_agg(v ORDER BY id),'[]') FROM pawbridge_community.home_videos v),"
                + "'board',(SELECT COALESCE(json_agg(b ORDER BY id),'[]') FROM pawbridge_community.home_video_board b),"
                + "'users',(SELECT COALESCE(json_agg(u ORDER BY user_id),'[]') FROM pawbridge_user.users u),"
                + "'favorites',(SELECT COALESCE(json_agg(f ORDER BY favorite_id),'[]') FROM pawbridge_user.favorites f),"
                + "'posts',(SELECT COALESCE(json_agg(p ORDER BY post_id),'[]') FROM pawbridge_community.posts p),"
                + "'comments',(SELECT COALESCE(json_agg(c ORDER BY comment_id),'[]') FROM pawbridge_community.comments c),"
                + "'f4comments',(SELECT COALESCE(json_agg(c ORDER BY comment_id),'[]') FROM pawbridge_f4_fixture.comments c),"
                + "'notes',(SELECT COALESCE(json_agg(n ORDER BY note_id),'[]') FROM pawbridge_f4_fixture.private_notes n),"
                + "'outbox',(SELECT COALESCE(json_agg(e ORDER BY outbox_id),'[]') FROM pawbridge_community.outbox_events e),"
                + "'search',(SELECT COALESCE(json_agg(s ORDER BY post_id),'[]') FROM pawbridge_community.post_search_documents s))::text)";
        try (Connection c = admin(); var s = c.createStatement(); var rows = s.executeQuery(sql)) { assertThat(rows.next()).isTrue(); return rows.getString(1); }
    }
    private void returned() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TransactionSynchronizationManager.hasResource(reader)).isFalse();
        assertThat(reader.getMaximumPoolSize()).isOne(); assertThat(reader.getHikariPoolMXBean().getActiveConnections()).isZero();
    }
}
