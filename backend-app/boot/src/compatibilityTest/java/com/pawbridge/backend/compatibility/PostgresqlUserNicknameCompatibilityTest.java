package com.pawbridge.backend.compatibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.user.internal.nickname.application.ChangeUserNickname;
import com.pawbridge.backend.boot.security.PostTokenAuthenticator;
import com.pawbridge.backend.user.internal.nickname.application.NicknameDuplicateException;
import com.pawbridge.backend.user.internal.nickname.application.UserNicknameStore;
import com.pawbridge.backend.user.internal.nickname.infrastructure.JdbcUserNicknameStore;
import com.pawbridge.backend.user.internal.nickname.web.UpdateNicknameRequest;
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException;
import com.pawbridge.userservice.dto.request.UpdateNicknameRequestDto;
import com.pawbridge.userservice.entity.Role;
import com.pawbridge.userservice.entity.User;
import com.pawbridge.userservice.jwt.JwtProvider;
import com.pawbridge.userservice.persistence.PostgresqlRollbackCharsetViolation;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.validation.Validation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Only owned synthetic rows. Both implementations use real proxies and their actual SQL/JPA commits. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PostgresqlUserNicknameCompatibilityTest {
    private static final long BASE = 9_970_000L;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-02T18:04:05.123456Z"), ZoneId.systemDefault());
    private final Set<Long> inserted = new LinkedHashSet<>();
    private final ObjectMapper mapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private F3PostgresqlFixture owner;
    private HikariDataSource writer;

    @BeforeAll void openOnlyRunOwnedHostedFixture() throws Exception {
        assertThat(ZoneId.systemDefault().getId()).isIn("UTC", "Asia/Seoul");
        owner = F3PostgresqlFixture.open();
        verifyOwnership();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(owner.reads().jdbcUrl()); config.setUsername(F3PostgresqlFixture.WRITER);
        config.setPassword(owner.writerPassword()); config.setMaximumPoolSize(1); config.setMinimumIdle(0);
        config.addDataSourceProperty("connectTimeout", "3"); config.addDataSourceProperty("socketTimeout", "12");
        config.setConnectionInitSql("SET statement_timeout=8000; SET lock_timeout=5000");
        config.setConnectionTimeout(2_000); config.setValidationTimeout(1_000); config.setPoolName("f13-nickname-writer");
        writer = new HikariDataSource(config);
    }

    @AfterEach void removeOnlyInsertedRowsAndVerifyReturnedWriter() throws Exception {
        if (owner != null) {
            try (Connection c = admin(); var s = c.prepareStatement("DELETE FROM pawbridge_user.users WHERE user_id=?")) {
                for (long id : inserted) { s.setLong(1, id); s.executeUpdate(); }
            }
        }
        inserted.clear();
        if (writer != null) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(TransactionSynchronizationManager.hasResource(writer)).isFalse();
            assertThat(writer.getMaximumPoolSize()).isOne();
            assertThat(writer.getHikariPoolMXBean().getActiveConnections()).isZero();
        }
    }

    @AfterAll void closeOnlyThisSyntheticWriter() { if (writer != null) writer.close(); }

    @Test void givenActualRequestDtoAndJackson__whenValidatingRawInputs__thenMatchConstraintsMessagesAndCoercion() throws Exception {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            for (String json : List.of("{}", "{\"nickname\":null}", "{\"nickname\":\"\"}", "{\"nickname\":\"a\"}",
                    "{\"nickname\":\"가A2\"}", "{\"nickname\":\"123\"}", "{\"nickname\":\"a b\"}",
                    "{\"nickname\":\"ㄱㄴ\"}", "{\"nickname\":\"ＡＢ\"}", "{\"nickname\":\"a😀\"}",
                    "{\"nickname\":true}", "{\"nickname\":1234}", "{\"nickname\":\"" + "a".repeat(30) + "\"}",
                    "{\"nickname\":\"" + "a".repeat(31) + "\"}")) {
                var old = mapper.readValue(json, UpdateNicknameRequestDto.class);
                var candidate = mapper.readValue(json, UpdateNicknameRequest.class);
                assertThat(candidate.nickname()).isEqualTo(old.getNickname());
                var expected = validator.validate(old).stream().map(v -> v.getPropertyPath() + ": " + v.getMessage()).toList();
                var actual = validator.validate(candidate).stream().map(v -> v.getPropertyPath() + ": " + v.getMessage()).toList();
                assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"새이름2", "NewName22"})
    void givenActualJpaAuditingAndCandidate__whenChangingNickname__thenRawTimestampAndOtherColumnsArePreserved(String requested) throws Exception {
        long oldId = BASE + 1, newId = BASE + 2;
        seed(oldId, "F13원본1"); seed(newId, "F13후보1");
        String before = immutable(newId);
        String expected;
        try (var oracle = oracle()) { oracle.update(oldId, requested); expected = mutable(oldId); }
        execute("DELETE FROM pawbridge_user.users WHERE user_id=" + oldId);
        try (var candidate = candidate()) { candidate.change.change(newId, requested); }
        assertThat(mutable(newId)).isEqualTo(expected);
        assertThat(immutable(newId)).isEqualTo(before);
    }

    @Test void givenExactStoredNickname__whenRequestingSameValue__thenBothReadInsideTransactionWithoutDuplicateQuerySaveOrAudit() throws Exception {
        seed(BASE + 1, "F13같음");
        String before = row(BASE + 1);
        try (var oracle = oracle()) {
            oracle.update(BASE + 1, "F13같음");
            assertThat(oracle.reads.get()).isOne(); assertThat(oracle.duplicateQueries.get()).isZero(); assertThat(oracle.saves.get()).isZero();
        }
        try (var candidate = candidate()) {
            candidate.change.change(BASE + 1, "F13같음");
            assertThat(candidate.store.reads).isOne(); assertThat(candidate.store.duplicates).isZero(); assertThat(candidate.store.updates).isZero();
        }
        assertThat(row(BASE + 1)).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"f13Alice", "F13다른이름"})
    void givenSelfCaseOnlyOrAnotherMemberDuplicate__whenChanging__thenBothRejectBeforeUpdateWithoutModifyingRows(String requested) throws Exception {
        seed(BASE + 1, "F13Alice"); seed(BASE + 2, "F13다른이름");
        String before = allOwnedRows();
        try (var oracle = oracle()) {
            assertThatThrownBy(() -> oracle.update(BASE + 1, requested)).isInstanceOf(com.pawbridge.userservice.exception.NicknameDuplicateException.class);
            assertThat(oracle.saves.get()).isZero();
        }
        try (var candidate = candidate()) {
            assertThatThrownBy(() -> candidate.change.change(BASE + 1, requested)).isInstanceOf(NicknameDuplicateException.class);
            assertThat(candidate.store.updates).isZero();
        }
        assertThat(allOwnedRows()).isEqualTo(before);
    }

    @Test void givenNoMember__whenChanging__thenMissingIsNotDuplicateAndNoMutationOccurs() throws Exception {
        String before = allOwnedRows();
        try (var oracle = oracle()) {
            assertThatThrownBy(() -> oracle.update(BASE + 1, "F13없는회원")).isInstanceOf(com.pawbridge.userservice.exception.UserNotFoundException.class);
        }
        try (var candidate = candidate()) {
            assertThatThrownBy(() -> candidate.change.change(BASE + 1, "F13없는회원")).isInstanceOf(UserNotFoundException.class);
            assertThat(candidate.store.duplicates).isZero(); assertThat(candidate.store.updates).isZero();
        }
        assertThat(allOwnedRows()).isEqualTo(before);
    }

    @Test void givenRevokedNicknameUpdatePrivilege__whenNoOpThenChangeThenRestore__thenNoOpWorksChangeRollsBackAndRecoveryCommits() throws Exception {
        seed(BASE + 1, "F13권한전"); String before = row(BASE + 1);
        execute("REVOKE UPDATE(nickname) ON pawbridge_user.users FROM " + F3PostgresqlFixture.WRITER);
        try (var candidate = candidate()) {
            candidate.change.change(BASE + 1, "F13권한전");
            assertThatThrownBy(() -> candidate.change.change(BASE + 1, "F13권한후")).satisfies(e -> assertThat(sqlState(e)).isEqualTo("42501"));
            assertThat(row(BASE + 1)).isEqualTo(before);
        } finally { execute("GRANT UPDATE(nickname) ON pawbridge_user.users TO " + F3PostgresqlFixture.WRITER); }
        try (var candidate = candidate()) { candidate.change.change(BASE + 1, "F13권한후"); }
        assertThat(scalar("SELECT nickname FROM pawbridge_user.users WHERE user_id=" + (BASE + 1))).isEqualTo("F13권한후");
    }

    @Test void givenConcurrentRoleAndPasswordChangeAfterRead__whenUpdatingNickname__thenNarrowCandidatePreservesItUnlikeLegacyFullRowFlush() throws Exception {
        seed(BASE + 1, "F13원본동시"); seed(BASE + 2, "F13후보동시");
        Runnable oldChange = () -> uncheckedSql("UPDATE pawbridge_user.users SET role='ROLE_ADMIN',password='F13_EXTERNAL_PASSWORD' WHERE user_id=" + (BASE + 1));
        try (var oracle = new F13LegacyNicknameOracle(owner, CLOCK, this::verifyOwnership, oldChange, () -> { })) {
            oracle.update(BASE + 1, "F13원본새값");
        }
        Runnable candidateChange = () -> uncheckedSql("UPDATE pawbridge_user.users SET role='ROLE_ADMIN',password='F13_EXTERNAL_PASSWORD' WHERE user_id=" + (BASE + 2));
        try (var candidate = new Candidate(writer, candidateChange, () -> { })) { candidate.change.change(BASE + 2, "F13후보새값"); }
        assertThat(scalar("SELECT role||':'||password FROM pawbridge_user.users WHERE user_id=" + (BASE + 1))).isEqualTo("ROLE_USER:F13_PRIVATE");
        assertThat(scalar("SELECT role||':'||password FROM pawbridge_user.users WHERE user_id=" + (BASE + 2))).isEqualTo("ROLE_ADMIN:F13_EXTERNAL_PASSWORD");
    }

    @Test void givenPhysicalRemovalAfterRead__whenUpdating__thenZeroRowsIsUnexpectedFailureWithoutRecreatingMember() throws Exception {
        seed(BASE + 1, "F13사라짐");
        try (var candidate = new Candidate(writer,
                () -> uncheckedSql("DELETE FROM pawbridge_user.users WHERE user_id=" + (BASE + 1)), () -> { })) {
            assertThatThrownBy(() -> candidate.change.change(BASE + 1, "F13새값")).isInstanceOf(IllegalStateException.class);
        }
        assertThat(scalar("SELECT count(*) FROM pawbridge_user.users WHERE user_id=" + (BASE + 1))).isEqualTo("0");
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void givenTwoAvailableChecksBeforeEitherUpdate__whenRacingUniqueNickname__thenOneCommitsAndOneSql23505RollsBackWithoutBusiness409(boolean legacy) throws Exception {
        seed(BASE + 1, "F13경합첫째"); seed(BASE + 2, "F13경합둘째");
        Map<Long,String> beforeRows = Map.of(BASE + 1, row(BASE + 1), BASE + 2, row(BASE + 2));
        Map<Long,String> beforeImmutable = Map.of(BASE + 1, immutable(BASE + 1), BASE + 2, immutable(BASE + 2));
        var barrier = new CyclicBarrier(2);
        Runnable meet = () -> { try { barrier.await(10, TimeUnit.SECONDS); } catch (Exception e) { throw new IllegalStateException(e); } };
        var executor = Executors.newFixedThreadPool(2);
        try (var first = legacy ? oracle(meet) : new Candidate(independentWriter(), () -> { }, meet);
             var second = legacy ? oracle(meet) : new Candidate(independentWriter(), () -> { }, meet)) {
            try {
            var a = executor.submit(() -> attempt(first, BASE + 1, "F13공통경합"));
            var b = executor.submit(() -> attempt(second, BASE + 2, "F13공통경합"));
            var outcomes = new ArrayList<Throwable>(); outcomes.add(a.get(20, TimeUnit.SECONDS)); outcomes.add(b.get(20, TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter(e -> e == null).count()).isOne();
            Throwable failure = outcomes.stream().filter(e -> e != null).findFirst().orElseThrow();
            assertThat(sqlState(failure)).isEqualTo("23505");
            assertThat(failure).isNotInstanceOf(NicknameDuplicateException.class)
                    .isNotInstanceOf(com.pawbridge.userservice.exception.NicknameDuplicateException.class);
            long loser = outcomes.get(0) != null ? BASE + 1 : BASE + 2;
            assertThat(row(loser)).isEqualTo(beforeRows.get(loser));
            for (long id : beforeImmutable.keySet()) assertThat(immutable(id)).isEqualTo(beforeImmutable.get(id));
            } finally {
                executor.shutdownNow();
                assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            }
        } finally { executor.shutdownNow(); }
        assertThat(scalar("SELECT count(*) FROM pawbridge_user.users WHERE nickname='F13공통경합' AND user_id BETWEEN " + (BASE + 1) + " AND " + (BASE + 2))).isEqualTo("1");
        assertThat(scalar("SELECT count(*) FROM pawbridge_user.users WHERE user_id BETWEEN " + (BASE + 1) + " AND " + (BASE + 2))).isEqualTo("2");
    }

    @Test void givenServiceCalledWithoutMvcValidation__whenRealCharsetCheckRejects__thenBothRetainNamedMetadataAndRollback() throws Exception {
        seed(BASE + 1, "F13문자원본"); seed(BASE + 2, "F13문자후보"); String before = allOwnedRows();
        try (var oracle = oracle()) {
            assertThatThrownBy(() -> oracle.update(BASE + 1, "F13😀")).satisfies(e -> {
                assertThat(sqlState(e)).isEqualTo("23514"); assertThat(PostgresqlRollbackCharsetViolation.matches(e)).isTrue();
            });
        }
        try (var candidate = candidate()) {
            assertThatThrownBy(() -> candidate.change.change(BASE + 2, "F13😀")).satisfies(e -> {
                assertThat(sqlState(e)).isEqualTo("23514"); assertThat(PostgresqlRollbackCharsetViolation.matches(e)).isTrue();
            });
        }
        assertThat(allOwnedRows()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"email", "password", "role", "created_at"})
    void givenMinimalWriter__whenAccessingUnrelatedUserColumns__thenSelectAndUpdateAreRejected(String column) throws Exception {
        for (String sql : List.of("SELECT " + column + " FROM pawbridge_user.users WHERE false",
                "UPDATE pawbridge_user.users SET " + column + "=NULL WHERE false")) {
            try (Connection c = application(); var s = c.createStatement()) {
                assertThatThrownBy(() -> s.execute(sql)).isInstanceOf(SQLException.class).satisfies(e -> assertThat(sqlState(e)).isEqualTo("42501"));
            }
        }
    }

    // Both timezone tasks refresh last, immediately before the subsequent packaged journey.
    @Test @Order(Integer.MAX_VALUE) void givenPinnedIssuerAndOwnedTokenFile__whenRefreshingBeforeJar__thenPreserveCasesAndAddF13Principal() throws Exception {
        verifyOwnership();
        String run = F3PostgresqlFixture.required("GITHUB_RUN_ID") + "-" + F3PostgresqlFixture.required("GITHUB_RUN_ATTEMPT");
        Path output = Path.of(F3PostgresqlFixture.required("F3_TEST_TOKENS_PATH")).toAbsolutePath().normalize();
        assertThat(output).isEqualTo(Path.of(F3PostgresqlFixture.required("RUNNER_TEMP")).toAbsolutePath()
                .normalize().resolve("f3-tokens-" + run + ".json"));
        assertThat(Files.isRegularFile(output)).isTrue();
        assertThat(Files.isSymbolicLink(output)).isFalse();
        assertThat(Files.getPosixFilePermissions(output)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
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
        String other = issuer.createAccessToken(tokenUser(9_980_001L, Role.ROLE_USER));
        assertThat(new PostTokenAuthenticator(key).authenticate("Bearer " + other)).isEqualTo(9_980_001L);
        tokens.put("F12_OTHER", other);
        tokens.put("F13_OTHER", other);
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
        assertThat(tokens.keySet()).containsAll(originalKeys).contains("F12_OTHER", "F13_OTHER");
        Files.writeString(output, mapper.writeValueAsString(tokens));
        Files.setPosixFilePermissions(output, PosixFilePermissions.fromString("rw-------"));
    }

    private User tokenUser(long id, Role role) {
        return User.builder().userId(id).email("f13-token@fixture.invalid").name("F13 token claim")
                .nickname("F13token").provider("GOOGLE").role(role).build();
    }

    private Throwable attempt(AutoCloseable implementation, long id, String nickname) {
        try {
            if (implementation instanceof F13LegacyNicknameOracle oracle) oracle.update(id, nickname);
            else ((Candidate) implementation).change.change(id, nickname);
            return null;
        } catch (RuntimeException failure) { return failure; }
    }

    private F13LegacyNicknameOracle oracle() { return new F13LegacyNicknameOracle(owner, CLOCK, this::verifyOwnership); }
    private F13LegacyNicknameOracle oracle(Runnable available) { return new F13LegacyNicknameOracle(owner, CLOCK, this::verifyOwnership, () -> { }, available); }
    private Candidate candidate() { return new Candidate(writer, () -> { }, () -> { }); }

    private final class Candidate implements AutoCloseable {
        final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        final ObservingStore store;
        final ChangeUserNickname change;
        Candidate(DataSource dataSource, Runnable afterRead, Runnable afterAvailable) {
            var jdbc = new JdbcTemplate(dataSource); jdbc.setQueryTimeout(5);
            store = new ObservingStore(new JdbcUserNicknameStore(jdbc, () -> LocalDateTime.now(CLOCK)), afterRead, afterAvailable);
            context.registerBean("writeTransactionManager", JdbcTransactionManager.class, () -> new JdbcTransactionManager(dataSource));
            context.registerBean(ChangeUserNickname.class, () -> new ChangeUserNickname(store));
            context.register(TransactionConfiguration.class);
            try { context.refresh(); change = context.getBean(ChangeUserNickname.class); assertThat(AopUtils.isCglibProxy(change)).isTrue(); }
            catch (RuntimeException | Error failure) { context.close(); throw failure; }
        }
        @Override public void close() { context.close(); }
    }

    private static final class ObservingStore implements UserNicknameStore {
        final UserNicknameStore delegate; final Runnable afterRead; final Runnable afterAvailable;
        int reads, duplicates, updates;
        ObservingStore(UserNicknameStore delegate, Runnable afterRead, Runnable afterAvailable) {
            this.delegate = delegate; this.afterRead = afterRead; this.afterAvailable = afterAvailable;
        }
        @Override public Optional<String> findNickname(long id) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
            reads++; var value = delegate.findNickname(id); afterRead.run(); return value;
        }
        @Override public boolean existsByNickname(String nickname) {
            duplicates++; boolean exists = delegate.existsByNickname(nickname); if (!exists) afterAvailable.run(); return exists;
        }
        @Override public void updateNickname(long id, String nickname) { updates++; delegate.updateNickname(id, nickname); }
    }

    @Configuration(proxyBeanMethods = false) @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionConfiguration { }

    private DataSource independentWriter() {
        verifyOwnership();
        var dataSource = new DriverManagerDataSource(owner.reads().jdbcUrl(), F3PostgresqlFixture.WRITER, owner.writerPassword());
        var options = new java.util.Properties(); options.setProperty("options", "-c statement_timeout=8000 -c lock_timeout=5000");
        options.setProperty("connectTimeout", "3"); options.setProperty("socketTimeout", "12");
        dataSource.setConnectionProperties(options); return dataSource;
    }
    private Connection application() throws SQLException { verifyOwnership(); return independentWriter().getConnection(); }
    private void seed(long id, String nickname) throws SQLException {
        try (Connection c = admin(); var s = c.prepareStatement("INSERT INTO pawbridge_user.users(user_id,nickname,email,name,provider,role,password,care_reg_no,provider_id,created_at,updated_at) VALUES(?,?,?,'F13 synthetic','LOCAL','ROLE_USER','F13_PRIVATE','F13_PRIVATE','F13_PRIVATE',TIMESTAMP '2025-01-01 18:01:02.123456',TIMESTAMP '2025-02-01 18:01:02.123456')")) {
            s.setLong(1, id); s.setString(2, nickname); s.setString(3, "f13-" + id + "@fixture.invalid"); s.executeUpdate(); inserted.add(id);
        }
    }
    private String row(long id) throws SQLException { return scalar("SELECT to_jsonb(u)::text FROM pawbridge_user.users u WHERE user_id=" + id); }
    private String mutable(long id) throws SQLException { return scalar("SELECT json_build_array(nickname,updated_at)::text FROM pawbridge_user.users WHERE user_id=" + id); }
    private String immutable(long id) throws SQLException { return scalar("SELECT md5((to_jsonb(u)-'nickname'-'updated_at')::text) FROM pawbridge_user.users u WHERE user_id=" + id); }
    private String allOwnedRows() throws SQLException { return scalar("SELECT md5(COALESCE(json_agg(u ORDER BY user_id)::text,'[]')) FROM pawbridge_user.users u WHERE user_id BETWEEN " + BASE + " AND " + (BASE + 100)); }
    private void execute(String sql) throws SQLException { try (Connection c = admin(); var s = c.createStatement()) { s.execute(sql); } }
    private void uncheckedSql(String sql) { try { execute(sql); } catch (SQLException failure) { throw new IllegalStateException(failure); } }
    private String scalar(String sql) throws SQLException {
        try (Connection c = admin(); var s = c.createStatement(); var rows = s.executeQuery(sql)) { assertThat(rows.next()).isTrue(); return rows.getString(1); }
    }
    private Connection admin() throws SQLException {
        Connection c = owner.admin();
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT 12,value,owner FROM pawbridge_f12_fixture.marker UNION ALL SELECT 13,value,owner FROM pawbridge_f13_fixture.marker ORDER BY 1")) {
            String run = System.getenv("GITHUB_RUN_ID") + "-" + System.getenv("GITHUB_RUN_ATTEMPT");
            for (int stage : new int[] {12, 13}) {
                assertThat(System.getenv("F" + stage + "_CI_OWNER")).isEqualTo(run);
                assertThat(r.next()).isTrue(); assertThat(r.getInt(1)).isEqualTo(stage);
                assertThat(r.getString(2)).isEqualTo("pawbridge-f" + stage + "-ci-only-v1"); assertThat(r.getString(3)).isEqualTo(run);
            }
            assertThat(r.next()).isFalse();
        } catch (SQLException | RuntimeException | Error failure) { c.close(); throw failure; }
        return c;
    }
    private void verifyOwnership() { try (Connection ignored = admin()) { } catch (SQLException failure) { throw new IllegalStateException(failure); } }
    private static String sqlState(Throwable error) {
        var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        var pending = new java.util.ArrayDeque<Throwable>(); pending.add(error);
        while (!pending.isEmpty()) {
            Throwable item = pending.removeFirst(); if (!seen.add(item)) continue;
            if (item instanceof SQLException sql && sql.getSQLState() != null) return sql.getSQLState();
            if (item.getCause() != null) pending.addLast(item.getCause());
            if (item instanceof SQLException sql && sql.getNextException() != null) pending.addLast(sql.getNextException());
        }
        return null;
    }
}
