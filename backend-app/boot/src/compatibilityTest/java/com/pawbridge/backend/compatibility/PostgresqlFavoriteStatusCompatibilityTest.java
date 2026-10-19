package com.pawbridge.backend.compatibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.boot.security.PostTokenAuthenticator;
import com.pawbridge.backend.user.internal.favorite.infrastructure.JdbcFavoriteStatusQuery;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Dedicated UTC, GitHub-hosted synthetic fixture only. No local/production PostgreSQL. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation.class)
class PostgresqlFavoriteStatusCompatibilityTest {
    private static final long BASE = 9_950_000L;
    private final Set<Long> ownedUsers = new LinkedHashSet<>();
    private final Set<Long> ownedFavorites = new LinkedHashSet<>();
    private F3PostgresqlFixture owner;
    private HikariDataSource reader;
    private JdbcFavoriteStatusQuery query;
    private F14LegacyFavoriteOracle oracle;

    @BeforeAll void openOnlyOwnedFixtureAndMinimalReader() throws Exception {
        assertThat(System.getProperty("user.timezone")).isEqualTo("UTC");
        owner = F3PostgresqlFixture.open();
        verifyOwnership();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(owner.reads().jdbcUrl());
        config.setUsername(owner.reads().applicationUser()); config.setPassword(owner.reads().applicationPassword());
        config.setReadOnly(true); config.setMaximumPoolSize(1); config.setMinimumIdle(0);
        config.setConnectionTimeout(2_000); config.setValidationTimeout(1_000); config.setPoolName("f14-favorite-reader");
        reader = new HikariDataSource(config);
        var jdbc = new JdbcTemplate(reader); jdbc.setQueryTimeout(3);
        var manager = new JdbcTransactionManager(reader); manager.setEnforceReadOnly(true);
        var tx = new TransactionTemplate(manager); tx.setReadOnly(true); tx.setTimeout(3);
        query = new JdbcFavoriteStatusQuery(jdbc, tx);
        oracle = new F14LegacyFavoriteOracle(owner, this::verifyOwnership);
        returned();
    }

    @AfterEach void removeOnlySuccessfullyInsertedRowsInForeignKeyOrder() throws Exception {
        if (owner != null) {
            try (Connection c = admin(); var s = c.prepareStatement("DELETE FROM pawbridge_user.favorites WHERE favorite_id=?")) {
                for (long id : ownedFavorites) { s.setLong(1, id); s.executeUpdate(); }
            }
            try (Connection c = admin(); var s = c.prepareStatement("DELETE FROM pawbridge_user.users WHERE user_id=?")) {
                for (long id : ownedUsers) { s.setLong(1, id); s.executeUpdate(); }
            }
        }
        ownedFavorites.clear(); ownedUsers.clear();
        if (reader != null) returned();
    }

    @AfterAll void closeOnlyOracleAndOwnReader() {
        try { if (oracle != null) oracle.close(); } finally { if (reader != null) reader.close(); }
    }

    @Test void givenDifferentUsersAndAnimals__whenChecking__thenActualDerivedQueryAndCandidateAgreeWithoutAnyWrites() throws Exception {
        user(BASE + 1); user(BASE + 2);
        favorite(BASE + 101, BASE + 1, BASE + 11);
        favorite(BASE + 102, BASE + 2, BASE + 12);
        String before = snapshot();
        compare(BASE + 1, BASE + 11, true);
        compare(BASE + 2, BASE + 11, false);
        compare(BASE + 1, BASE + 12, false);
        compare(BASE + 2, BASE + 12, true);
        compare(BASE + 3, BASE + 11, false); // no User row
        compare(BASE + 1, BASE + 13, false); // no Favorite or Animal lookup
        assertThat(snapshot()).isEqualTo(before); returned();
    }

    @ParameterizedTest @ValueSource(longs = {0, -1, Long.MIN_VALUE, Long.MAX_VALUE})
    void givenRepresentableAnimalIdWithoutPositiveConstraint__whenChecking__thenExactStoredLongIsUsed(long animalId) throws Exception {
        user(BASE + 1); favorite(BASE + 101, BASE + 1, animalId);
        String before = snapshot(); compare(BASE + 1, animalId, true);
        compare(BASE + 2, animalId, false);
        assertThat(snapshot()).isEqualTo(before); returned();
    }

    @ParameterizedTest @ValueSource(strings = {"user_id", "animal_id"})
    void givenRequiredSelectRevoked__whenCheckingThenRestoring__thenErrorIsNotFalseAndThePoolRecovers(String column) throws Exception {
        user(BASE + 1); favorite(BASE + 101, BASE + 1, BASE + 11);
        String before = snapshot();
        execute("REVOKE SELECT(" + column + ") ON pawbridge_user.favorites FROM pawbridge_f2_reader");
        try {
            assertThatThrownBy(() -> query.isFavorite(BASE + 1, BASE + 11)).isInstanceOf(DataAccessException.class);
            assertThat(snapshot()).isEqualTo(before); returned();
        } finally { execute("GRANT SELECT(" + column + ") ON pawbridge_user.favorites TO pawbridge_f2_reader"); }
        compare(BASE + 1, BASE + 11, true); assertThat(snapshot()).isEqualTo(before); returned();
    }

    @ParameterizedTest @ValueSource(strings = {"favorite_id", "created_at"})
    void givenMinimalReader__whenSelectingUnusedColumns__thenAccessIsDeniedAndExistsStillWorks(String column) throws Exception {
        user(BASE + 1); favorite(BASE + 101, BASE + 1, BASE + 11);
        String before = snapshot();
        try (Connection c = reader.getConnection()) {
            assertThatThrownBy(() -> {
                try (var s = c.createStatement()) { s.executeQuery("SELECT " + column + " FROM pawbridge_user.favorites WHERE false"); }
            }).isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
        }
        compare(BASE + 1, BASE + 11, true); assertThat(snapshot()).isEqualTo(before); returned();
    }

    @Test @Order(Integer.MAX_VALUE)
    void givenOriginalIssuerAndOwnedTokenFile__whenPreparingJarPrincipals__thenPreserveEveryExistingCaseAndAddF14Other() throws Exception {
        verifyOwnership();
        String run = F3PostgresqlFixture.required("GITHUB_RUN_ID") + "-" + F3PostgresqlFixture.required("GITHUB_RUN_ATTEMPT");
        Path file = Path.of(F3PostgresqlFixture.required("F3_TEST_TOKENS_PATH")).toAbsolutePath().normalize();
        assertThat(file).isEqualTo(Path.of(F3PostgresqlFixture.required("RUNNER_TEMP")).toAbsolutePath().normalize()
                .resolve("f3-tokens-" + run + ".json"));
        assertThat(Files.isSymbolicLink(file)).isFalse(); assertThat(Files.isRegularFile(file)).isTrue();
        assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        var mapper = new ObjectMapper();
        Map<String, String> tokens = new LinkedHashMap<>(mapper.readValue(Files.readString(file), new TypeReference<Map<String, String>>() { }));
        Set<String> original = Set.copyOf(tokens.keySet());
        String secret = F3PostgresqlFixture.required("F3_TEST_JWT_SECRET");
        var issuer = new JwtProvider(secret, 600_000, 600_000);
        var auth = new PostTokenAuthenticator(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)));
        for (var entry : Map.of("ROLE_USER", 42L, "F14_OTHER", 9_980_001L).entrySet()) {
            String token = issuer.createAccessToken(tokenUser(entry.getValue()));
            assertThat(auth.authenticate("Bearer " + token)).isEqualTo(entry.getValue()); tokens.put(entry.getKey(), token);
        }
        tokens.put("expired", new JwtProvider(secret, -60_000, 600_000).createAccessToken(tokenUser(42)));
        assertThat(tokens.keySet()).containsAll(original).contains("F14_OTHER");
        Files.writeString(file, mapper.writeValueAsString(tokens));
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")); returned();
    }

    private User tokenUser(long id) {
        return User.builder().userId(id).email("f14-token@fixture.invalid").name("F14 token claim")
                .nickname("F14token").provider("GOOGLE").role(Role.ROLE_USER).build();
    }

    private void compare(long userId, long animalId, boolean expected) {
        assertThat(oracle.exists(userId, animalId)).isEqualTo(expected);
        assertThat(query.isFavorite(userId, animalId)).isEqualTo(expected);
    }

    private void user(long id) throws SQLException {
        assertThat(ownedUsers).doesNotContain(id);
        try (Connection c = admin(); var s = c.prepareStatement("INSERT INTO pawbridge_user.users(user_id,email,name,nickname,provider,role) VALUES(?,?,?,?,?,?)")) {
            s.setLong(1, id); s.setString(2, "f14-" + id + "@fixture.invalid"); s.setString(3, "F14 synthetic");
            s.setString(4, "F14user" + id); s.setString(5, "GOOGLE"); s.setString(6, "ROLE_USER");
            s.executeUpdate(); ownedUsers.add(id);
        }
    }

    private void favorite(long id, long userId, long animalId) throws SQLException {
        assertThat(ownedFavorites).doesNotContain(id);
        try (Connection c = admin(); var s = c.prepareStatement("INSERT INTO pawbridge_user.favorites(favorite_id,user_id,animal_id,created_at) VALUES(?,?,?,TIMESTAMP '2026-01-01 00:00:00')")) {
            s.setLong(1, id); s.setLong(2, userId); s.setLong(3, animalId); s.executeUpdate(); ownedFavorites.add(id);
        }
    }

    private String snapshot() throws SQLException {
        String sql = "SELECT md5(json_build_object("
                + "'users',(SELECT COALESCE(json_agg(u ORDER BY user_id),'[]') FROM pawbridge_user.users u),"
                + "'favorites',(SELECT COALESCE(json_agg(f ORDER BY favorite_id),'[]') FROM pawbridge_user.favorites f),"
                + "'posts',(SELECT COALESCE(json_agg(p ORDER BY post_id),'[]') FROM pawbridge_community.posts p),"
                + "'comments',(SELECT COALESCE(json_agg(c ORDER BY comment_id),'[]') FROM pawbridge_community.comments c),"
                + "'notes',(SELECT COALESCE(json_agg(n ORDER BY note_id),'[]') FROM pawbridge_f4_fixture.private_notes n),"
                + "'outbox',(SELECT COALESCE(json_agg(e ORDER BY outbox_id),'[]') FROM pawbridge_community.outbox_events e),"
                + "'search',(SELECT COALESCE(json_agg(s ORDER BY post_id),'[]') FROM pawbridge_community.post_search_documents s))::text)";
        try (Connection c = admin(); var s = c.createStatement(); var r = s.executeQuery(sql)) { assertThat(r.next()).isTrue(); return r.getString(1); }
    }

    private Connection admin() throws SQLException {
        Connection c = owner.admin();
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT 13,value,owner FROM pawbridge_f13_fixture.marker UNION ALL SELECT 14,value,owner FROM pawbridge_f14_fixture.marker ORDER BY 1")) {
            String expected = F3PostgresqlFixture.required("GITHUB_RUN_ID") + "-" + F3PostgresqlFixture.required("GITHUB_RUN_ATTEMPT");
            for (int stage : new int[]{13, 14}) {
                assertThat(F3PostgresqlFixture.required("F" + stage + "_CI_OWNER")).isEqualTo(expected);
                assertThat(r.next()).isTrue(); assertThat(r.getInt(1)).isEqualTo(stage);
                assertThat(r.getString(2)).isEqualTo("pawbridge-f" + stage + "-ci-only-v1"); assertThat(r.getString(3)).isEqualTo(expected);
            }
            assertThat(r.next()).isFalse();
        } catch (SQLException | RuntimeException | Error e) { c.close(); throw e; }
        return c;
    }
    private void verifyOwnership() { try (Connection ignored = admin()) { } catch (SQLException e) { throw new IllegalStateException(e); } }
    private void execute(String sql) throws SQLException { try (Connection c = admin(); var s = c.createStatement()) { s.execute(sql); } }
    private void returned() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TransactionSynchronizationManager.hasResource(reader)).isFalse();
        assertThat(reader.getMaximumPoolSize()).isOne(); assertThat(reader.getHikariPoolMXBean().getActiveConnections()).isZero();
    }
}
