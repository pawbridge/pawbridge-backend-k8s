package com.pawbridge.backend.compatibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pawbridge.backend.boot.security.PostTokenAuthenticator;
import com.pawbridge.backend.user.internal.nickname.infrastructure.JdbcUserNicknameQuery;
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException;
import com.pawbridge.backend.user.internal.profile.application.UserProfile;
import com.pawbridge.backend.user.internal.profile.infrastructure.JdbcUserProfileQuery;
import com.pawbridge.userservice.entity.Role;
import com.pawbridge.userservice.entity.User;
import com.pawbridge.userservice.jwt.JwtProvider;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Same run-owned synthetic DB only; profile reads never inherit full User entity permissions. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class PostgresqlUserProfileCompatibilityTest {
    private static final long BASE = 9_990_000L;
    private static final long OTHER_HTTP_USER = 9_980_001L;
    private static final String PRIVATE = "F12_SYNTHETIC_PRIVATE_DO_NOT_RETURN";
    private static final Set<String> FIELDS = Set.of("userId", "email", "name", "nickname", "provider", "role", "createdAt");
    private final ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
    private final Set<Long> ownedUsers = new LinkedHashSet<>();
    private F3PostgresqlFixture owner;
    private HikariDataSource reader;
    private JdbcUserProfileQuery profiles;
    private JdbcUserNicknameQuery nicknames;
    private F12LegacyUserProfileOracle oracle;

    @BeforeAll
    void openOnlyTheOwnedHostedProfileFixture() throws Exception {
        assertThat(System.getenv("GITHUB_ACTIONS")).isEqualTo("true");
        assertThat(System.getenv("RUNNER_ENVIRONMENT")).isEqualTo("github-hosted");
        assertThat(System.getenv("RUNNER_OS")).isEqualTo("Linux");
        assertThat(System.getenv("GITHUB_REPOSITORY")).isEqualTo("pawbridge/pawbridge-backend-k8s");
        assertThat(ZoneId.systemDefault().getId()).isIn("UTC", "Asia/Seoul");
        owner = F3PostgresqlFixture.open();
        verifyOwnership();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(owner.reads().jdbcUrl());
        config.setUsername(owner.reads().applicationUser());
        config.setPassword(owner.reads().applicationPassword());
        config.setReadOnly(true); config.setMaximumPoolSize(1); config.setMinimumIdle(0);
        config.setConnectionTimeout(2_000); config.setValidationTimeout(1_000);
        config.setPoolName("f12-profile-reader");
        reader = new HikariDataSource(config);
        JdbcTemplate jdbc = new JdbcTemplate(reader); jdbc.setQueryTimeout(3);
        JdbcTransactionManager manager = new JdbcTransactionManager(reader); manager.setEnforceReadOnly(true);
        TransactionTemplate transaction = new TransactionTemplate(manager);
        transaction.setReadOnly(true); transaction.setTimeout(3);
        profiles = new JdbcUserProfileQuery(jdbc, transaction);
        nicknames = new JdbcUserNicknameQuery(jdbc, transaction);
        oracle = new F12LegacyUserProfileOracle(owner, this::verifyOwnership);
        returned();
    }

    @AfterEach
    void removeOnlySuccessfullyInsertedProfileIdentitiesAndCheckReturnedPool() throws Exception {
        if (owner != null) {
            try (Connection connection = admin(); var statement = connection.prepareStatement(
                    "DELETE FROM pawbridge_user.users WHERE user_id=?")) {
                for (long id : ownedUsers) { statement.setLong(1, id); statement.executeUpdate(); }
            }
        }
        ownedUsers.clear();
        if (reader != null) returned();
    }

    @AfterAll
    void closeOnlyThisOracleAndReaderPool() {
        try { if (oracle != null) oracle.close(); }
        finally { if (reader != null) reader.close(); }
    }

    static Stream<Arguments> profiles() {
        return Stream.of(
                Arguments.of("ROLE_USER", "프로필 원문", "LOCAL", "2025-01-01T18:04:05.123456"),
                Arguments.of("ROLE_ADMIN", "", "GOOGLE", null),
                Arguments.of("ROLE_SHELTER", "  ", "LOCAL", "2025-12-31T18:59:59.999999"),
                Arguments.of("ROLE_USER", "  원문  ", "GOOGLE", "2026-01-01T00:00:00.000001"),
                Arguments.of("ROLE_ADMIN", "관리자", "LOCAL", "2025-03-01T03:04:05.123456"),
                Arguments.of("ROLE_SHELTER", "보호소", "GOOGLE", null));
    }

    @ParameterizedTest
    @MethodSource("profiles")
    void givenRealUserMappingsAndStoredProfile__whenReading__thenExactlySevenFieldsAndNullableDateMatchJpaJson(
            String role, String nickname, String provider, String timestamp) throws Exception {
        seed(BASE + 1, role, nickname, provider, timestamp);
        String before = databaseState();
        JsonNode expected = mapper.<JsonNode>valueToTree(oracle.read(BASE + 1).orElseThrow());
        UserProfile actual = profiles.require(BASE + 1);
        JsonNode response = mapper.valueToTree(actual);
        assertThat(response).isEqualTo(expected);
        Set<String> fields = new LinkedHashSet<>(); response.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).isEqualTo(FIELDS);
        assertThat(response.get("nickname").asText()).isEqualTo(nickname);
        assertThat(response.get("provider").asText()).isEqualTo(provider);
        assertThat(response.get("role").asText()).isEqualTo(role);
        if (timestamp == null) assertThat(response.get("createdAt").isNull()).isTrue();
        assertThat(mapper.writeValueAsString(response))
                .doesNotContain(PRIVATE, "password", "providerId", "careRegNo", "updatedAt");
        assertThat(nicknames.findNickname(BASE + 1)).contains(nickname);
        assertThat(databaseState()).isEqualTo(before);
        returned();
    }

    @Test
    void givenNoUserThenASeparateValidUser__whenReading__thenMissingIsNotFallbackAndTheNextReadRecovers() throws Exception {
        String before = databaseState();
        assertThat(oracle.read(BASE + 2)).isEmpty();
        assertThatThrownBy(() -> profiles.require(BASE + 2)).isInstanceOf(UserNotFoundException.class);
        assertThat(databaseState()).isEqualTo(before); returned();
        seed(BASE + 3, "ROLE_USER", "", "LOCAL", null);
        assertThat(profiles.require(BASE + 3).nickname()).isEmpty();
        returned();
    }

    @ParameterizedTest
    @ValueSource(strings = {"user_id", "email", "name", "nickname", "provider", "role", "created_at"})
    void givenOneRequiredSelectPrivilegeMissing__whenReadingThenRestoring__thenFailureDoesNotBecomeMissingAndNextReadRecovers(String column)
            throws Exception {
        seed(BASE + 4, "ROLE_USER", "조회 회복", "LOCAL", "2025-01-01T18:04:05.123456");
        String before = databaseState();
        execute("REVOKE SELECT(" + column + ") ON pawbridge_user.users FROM pawbridge_f2_reader");
        try {
            assertThatThrownBy(() -> profiles.require(BASE + 4)).isInstanceOf(DataAccessException.class)
                    .isNotInstanceOf(UserNotFoundException.class);
            assertThat(databaseState()).isEqualTo(before); returned();
        } finally {
            execute("GRANT SELECT(" + column + ") ON pawbridge_user.users TO pawbridge_f2_reader");
        }
        assertThat(mapper.<JsonNode>valueToTree(profiles.require(BASE + 4)))
                .isEqualTo(mapper.<JsonNode>valueToTree(oracle.read(BASE + 4).orElseThrow()));
        assertThat(databaseState()).isEqualTo(before); returned();
    }

    @ParameterizedTest
    @ValueSource(strings = {"password", "provider_id", "care_reg_no", "updated_at"})
    void givenSevenColumnReader__whenSelectingPrivateEntityColumns__thenPrivilegeDeniedAndPublicProjectionStillWorks(String column)
            throws Exception {
        seed(BASE + 5, "ROLE_USER", "민감열 거부", "LOCAL", null);
        String before = databaseState();
        try (Connection connection = application()) {
            assertThatThrownBy(() -> {
                try (var statement = connection.createStatement()) {
                    statement.executeQuery("SELECT " + column + " FROM pawbridge_user.users WHERE user_id=" + (BASE + 5));
                }
            }).isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("42501"));
        }
        assertThat(profiles.require(BASE + 5).userId()).isEqualTo(BASE + 5);
        assertThat(databaseState()).isEqualTo(before); returned();
    }

    @ParameterizedTest
    @ValueSource(strings = {"INSERT", "UPDATE", "DELETE"})
    void givenReadOnlyApplicationRole__whenAttemptingUserWrites__thenPrivilegesRejectEvenOutsideTheReadOnlyPool(String operation)
            throws Exception {
        seed(BASE + 6, "ROLE_USER", "쓰기 거부", "LOCAL", null);
        String sql = switch (operation) {
            // Empty predicates keep even a misconfigured fixture from changing a row.
            case "INSERT" -> "INSERT INTO pawbridge_user.users(user_id,email,name,nickname,provider,role) "
                    + "SELECT -1,'synthetic@fixture.invalid','synthetic','synthetic','LOCAL','ROLE_USER' WHERE false";
            case "UPDATE" -> "UPDATE pawbridge_user.users SET nickname=nickname WHERE false";
            default -> "DELETE FROM pawbridge_user.users WHERE false";
        };
        String before = databaseState();
        try (Connection connection = application()) {
            connection.setReadOnly(false);
            assertThatThrownBy(() -> {
                try (var statement = connection.createStatement()) { statement.executeUpdate(sql); }
            }).isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("42501"));
        }
        assertThat(profiles.require(BASE + 6).userId()).isEqualTo(BASE + 6);
        assertThat(databaseState()).isEqualTo(before); returned();
    }

    @Test
    void givenTwoCurrentStoredProfiles__whenReadingAgainAfterAnExternalChange__thenReturnOnlyTheRequestedCurrentDatabaseValues()
            throws Exception {
        seed(BASE + 7, "ROLE_USER", "첫 회원", "LOCAL", null);
        seed(BASE + 8, "ROLE_SHELTER", "다른 회원", "GOOGLE", "2025-01-01T18:04:05.123456");
        String before = databaseState();
        assertThat(profiles.require(BASE + 7).nickname()).isEqualTo("첫 회원");
        assertThat(profiles.require(BASE + 8).nickname()).isEqualTo("다른 회원");
        assertThat(databaseState()).isEqualTo(before); returned();
        execute("UPDATE pawbridge_user.users SET role='ROLE_ADMIN',nickname='현재 저장 이름',"
                + "created_at=TIMESTAMP '2026-01-01 18:04:05.123456' WHERE user_id=" + (BASE + 7));
        before = databaseState();
        assertThat(mapper.<JsonNode>valueToTree(profiles.require(BASE + 7)))
                .isEqualTo(mapper.<JsonNode>valueToTree(oracle.read(BASE + 7).orElseThrow()));
        assertThat(profiles.require(BASE + 7).role()).isEqualTo("ROLE_ADMIN");
        assertThat(profiles.require(BASE + 8).nickname()).isEqualTo("다른 회원");
        assertThat(databaseState()).isEqualTo(before); returned();
    }

    @Test
    void givenPinnedIssuerAndRunOwnedTokenFile__whenRefreshingSyntheticInputs__thenPreserveTokenCasesAndAddASecondPrincipal()
            throws Exception {
        verifyOwnership();
        String run = F3PostgresqlFixture.required("GITHUB_RUN_ID") + "-" + F3PostgresqlFixture.required("GITHUB_RUN_ATTEMPT");
        Path output = Path.of(F3PostgresqlFixture.required("F3_TEST_TOKENS_PATH")).toAbsolutePath().normalize();
        assertThat(output).isEqualTo(Path.of(F3PostgresqlFixture.required("RUNNER_TEMP")).toAbsolutePath()
                .normalize().resolve("f3-tokens-" + run + ".json"));
        assertThat(Files.isRegularFile(output)).isTrue();
        Map<String, String> tokens = new LinkedHashMap<>(mapper.readValue(Files.readString(output), new TypeReference<Map<String, String>>() { }));
        Set<String> originalKeys = Set.copyOf(tokens.keySet());
        String secret = F3PostgresqlFixture.required("F3_TEST_JWT_SECRET");
        var key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        var issuer = new JwtProvider(secret, 600_000, 600_000);
        for (Role role : Role.values()) {
            User user = tokenUser(42, role);
            String token = issuer.createAccessToken(user);
            assertThat(new PostTokenAuthenticator(key).authenticate("Bearer " + token)).isEqualTo(42L);
            tokens.put(role.name(), token);
        }
        String other = issuer.createAccessToken(tokenUser(OTHER_HTTP_USER, Role.ROLE_USER));
        assertThat(new PostTokenAuthenticator(key).authenticate("Bearer " + other)).isEqualTo(OTHER_HTTP_USER);
        tokens.put("F12_OTHER", other);
        tokens.put("refresh", issuer.createRefreshToken());
        tokens.put("expired", new JwtProvider(secret, -60_000, 600_000).createAccessToken(tokenUser(42, Role.ROLE_USER)));
        tokens.put("forged", new JwtProvider("f12-wrong-synthetic-secret-at-least-sixty-four-characters-0123456789012345",
                600_000, 600_000).createAccessToken(tokenUser(42, Role.ROLE_USER)));
        Map<String, Map<String, Object>> invalid = Map.of(
                "missingId", Map.of("role", "ROLE_USER"), "zeroId", Map.of("userId", 0L, "role", "ROLE_USER"),
                "negativeId", Map.of("userId", -1L, "role", "ROLE_USER"), "stringId", Map.of("userId", "42", "role", "ROLE_USER"),
                "missingRole", Map.of("userId", 42L), "numericRole", Map.of("userId", 42L, "role", 42));
        invalid.forEach((name, claims) -> tokens.put(name, Jwts.builder().claims(claims).issuedAt(new Date())
                .expiration(Date.from(Instant.now().plusSeconds(600))).signWith(key).compact()));
        assertThat(tokens.keySet()).containsAll(originalKeys).contains("F12_OTHER");
        Files.writeString(output, mapper.writeValueAsString(tokens));
        Files.setPosixFilePermissions(output, PosixFilePermissions.fromString("rw-------"));
        returned();
    }

    private User tokenUser(long id, Role role) {
        return User.builder().userId(id).email("f12-token-claim@fixture.invalid").name("F12 토큰 클레임")
                .nickname("F12 token nickname").provider("GOOGLE").role(role).build();
    }

    private void seed(long id, String role, String nickname, String provider, String created) throws Exception {
        assertThat(ownedUsers).doesNotContain(id);
        try (Connection connection = admin(); var statement = connection.prepareStatement(
                "INSERT INTO pawbridge_user.users(user_id,email,name,nickname,provider,role,created_at,password,provider_id,care_reg_no,updated_at) "
                        + "VALUES(?,?,?,?,?,?,?::timestamp,?,?,?,TIMESTAMP '2024-01-01 01:02:03')")) {
            statement.setLong(1, id); statement.setString(2, "f12-" + id + "@fixture.invalid");
            statement.setString(3, "F12 합성 회원"); statement.setString(4, nickname);
            statement.setString(5, provider); statement.setString(6, role); statement.setString(7, created);
            statement.setString(8, PRIVATE); statement.setString(9, PRIVATE); statement.setString(10, PRIVATE);
            statement.executeUpdate(); ownedUsers.add(id);
        }
    }

    private String databaseState() throws SQLException {
        return scalar("SELECT md5(json_build_object("
                + "'users',(SELECT COALESCE(json_agg(u ORDER BY user_id),'[]') FROM pawbridge_user.users u),"
                + "'posts',(SELECT COALESCE(json_agg(p ORDER BY post_id),'[]') FROM pawbridge_community.posts p),"
                + "'comments',(SELECT COALESCE(json_agg(c ORDER BY comment_id),'[]') FROM pawbridge_community.comments c),"
                + "'notes',(SELECT COALESCE(json_agg(n ORDER BY note_id),'[]') FROM pawbridge_f4_fixture.private_notes n),"
                + "'outbox',(SELECT COALESCE(json_agg(e ORDER BY outbox_id),'[]') FROM pawbridge_community.outbox_events e),"
                + "'search',(SELECT COALESCE(json_agg(s ORDER BY post_id),'[]') FROM pawbridge_community.post_search_documents s))::text)");
    }

    private Connection admin() throws SQLException {
        Connection connection = owner.admin();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT 9,value,owner FROM pawbridge_f9_fixture.marker UNION ALL SELECT 10,value,owner FROM pawbridge_f10_fixture.marker "
                        + "UNION ALL SELECT 11,value,owner FROM pawbridge_f11_fixture.marker "
                        + "UNION ALL SELECT 12,value,owner FROM pawbridge_f12_fixture.marker ORDER BY 1")) {
            String expected = System.getenv("GITHUB_RUN_ID") + "-" + System.getenv("GITHUB_RUN_ATTEMPT");
            for (int stage : new int[]{9, 10, 11, 12}) {
                assertThat(System.getenv("F" + stage + "_CI_OWNER")).isEqualTo(expected);
                assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(stage);
                assertThat(rows.getString(2)).isEqualTo("pawbridge-f" + stage + "-ci-only-v1");
                assertThat(rows.getString(3)).isEqualTo(expected);
            }
            assertThat(rows.next()).isFalse();
        } catch (SQLException | RuntimeException | Error failure) {
            connection.close(); throw failure;
        }
        return connection;
    }

    private void verifyOwnership() {
        try (Connection ignored = admin()) { }
        catch (SQLException failure) { throw new IllegalStateException(failure); }
    }

    private Connection application() throws SQLException {
        verifyOwnership();
        return DriverManager.getConnection(owner.reads().jdbcUrl(), owner.reads().applicationUser(), owner.reads().applicationPassword());
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = admin(); var statement = connection.createStatement()) { statement.execute(sql); }
    }

    private String scalar(String sql) throws SQLException {
        try (Connection connection = admin(); var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).isTrue(); return rows.getString(1);
        }
    }

    private void returned() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TransactionSynchronizationManager.hasResource(reader)).isFalse();
        assertThat(reader.getMaximumPoolSize()).isOne();
        assertThat(reader.getHikariPoolMXBean().getActiveConnections()).isZero();
    }
}
