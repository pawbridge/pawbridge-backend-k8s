package com.pawbridge.backend.compatibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.user.internal.session.application.AuthSessions;
import com.pawbridge.backend.user.internal.session.infrastructure.JdbcAuthSessions;
import com.pawbridge.backend.user.internal.session.infrastructure.JwtSessionTokens;
import com.pawbridge.backend.user.internal.session.web.AuthSessionExceptionHandler;
import com.pawbridge.userservice.entity.Role;
import com.pawbridge.userservice.jwt.JwtProvider;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongConsumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Selected source AuthService methods with actual JpaTM/derived repository, not a complete OAuth/login context. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresqlAuthSessionCompatibilityTest {
    private static final long BASE = 9_985_800;
    private static final long ACCESS_MS = 120_999, REFRESH_MS = 600_999;
    private static final String ORIGINAL_RECEIPT_SHA = "96e0d308622eedc6d41415fc6618af2114ade873cad7c50ca505690dcf4c4891";
    private static final String MEMBER_PROOF="source_sha=f06a72a06bd26662c647bde780dfc7a27bf7349c\nfixture=F29 plus User deletion and installed-chat cleanup\nnew_business_ddl=0\nsynthetic_tables_added=6\nsynthetic_identity_sequences_added=2\nnew_schema=0\nnew_marker=0\nsnapshot_tables=24\nreader_delta=0\nnew_sequence_acl=0\ncf93ed908b18f63f59832cd0fe5bcf500ccf9e4c39972deeda5de446c4dd953d  member-ddl-fragment-0\neb3fe612fe9d4aff8372539b0d59e1da4c34266036ce88f1ed0ba51b88f5e139  member-ddl-fragment-1\nb705222e7a9c9a6964628c937cff63470e1dd30103b27111ae3c5d94d284e1ec  member-ddl-fragment-2\n7afb8a3d992eba1258d61a610a418133f56766e9def3eb2e105c584bedcd2fdb  member-ddl-fragment-3\n5604c03964f051343785c2d30f9bf20022d6a0c867152b6175168c4f5ea81754  member-exact-grants\n";
    private static final String AUTH_ORACLE_SHA = "37f8491027f62e11e85f08fadd7afa2cfc5c8bc0cc93753935c8b96f5d67a58f";
    private static final String AUTH_PROOF = "source_sha=f06a72a06bd26662c647bde780dfc7a27bf7349c\nfixture=administrator User update plus refresh/logout writer\nnew_business_ddl=0\nnew_marker=0\nsnapshot_tables=24\nreader_delta=0\nnew_sequence_acl=0\nwriter_user_select=user_id,nickname,role,care_reg_no,email,name\nwriter_refresh_select=refresh_token_id,token,user_id,expires_at\nwriter_refresh_update=token,expires_at\nwriter_refresh_insert=0\ndadc3f720131eda804e0bec3aa6178fb6c15406cdd736bc50f48d1334d325aed  auth-session-exact-grants\n";
    private static final String COMMIT_REJECTION = "auth_session_owned_commit_rejection";
    private final ObjectMapper mapper = new ObjectMapper();
    private final Set<Long> users = new LinkedHashSet<>();
    private final Map<Long, Long> refreshRows = new LinkedHashMap<>();
    private F3PostgresqlFixture owner;
    private HikariDataSource writer;
    private String baseline;
    private ch.qos.logback.classic.Logger sqlLogger;
    private ch.qos.logback.classic.Level priorSqlLevel;

    @BeforeAll void openOnlyOwnedLateFixtureAndProtectTokenBearingSqlDiagnostics() throws Exception {
        assertThat(ZoneId.systemDefault().getId()).isIn("UTC", "Asia/Seoul");
        owner = F3PostgresqlFixture.open(); verifyOwnership();
        assertThat(scalar("SELECT count(*) FROM pawbridge_user.users WHERE user_id BETWEEN " + BASE + " AND " + (BASE + 99))).isEqualTo("0");
        assertThat(scalar("SELECT count(*) FROM pawbridge_user.refresh_tokens WHERE refresh_token_id BETWEEN " + BASE + " AND " + (BASE + 99)
                + " OR user_id BETWEEN " + BASE + " AND " + (BASE + 99))).isEqualTo("0");
        // PostgreSQL error details may contain the raw UNIQUE token. Never publish those framework diagnostics.
        sqlLogger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("org.hibernate.engine.jdbc.spi.SqlExceptionHelper");
        priorSqlLevel = sqlLogger.getLevel(); sqlLogger.setLevel(ch.qos.logback.classic.Level.OFF);
        writer = writer("auth-session-writer", 1); baseline = snapshot();
    }
    @AfterEach void removeOnlyAcknowledgedOwnedRowsAndCheckAllTwentyFourTables() throws Exception {
        try { cleanup(); }
        finally { returned(writer); }
        if (baseline != null) assertThat(snapshot().equals(baseline)).as("All twenty-four raw tables restore without logging token values").isTrue();
    }
    @AfterAll void closeWriterAndRestoreDiagnosticPolicy() {
        try { if (writer != null) writer.close(); }
        finally { if (sqlLogger != null) sqlLogger.setLevel(priorSqlLevel); }
    }

    @Test void givenCurrentClaimsAndMicrosecondStoredExpiry__whenRotatingThenReplaying__thenPreserveCreatedAtAndOnlyChangeTheSelectedRefresh() throws Exception {
        for (boolean original : new boolean[] {true, false}) {
            seedUser(BASE + 1); seedUser(BASE + 2);
            String old = seedRefresh(BASE + 21, BASE + 1, 300_000, LocalDateTime.now().plusMinutes(4));
            seedRefresh(BASE + 22, BASE + 2, 310_000, LocalDateTime.now().plusMinutes(4));
            String created = scalar("SELECT created_at::text FROM pawbridge_user.refresh_tokens WHERE refresh_token_id=" + (BASE + 21));
            String before = snapshot(); LocalDateTime earliest = LocalDateTime.now();
            try (var round = round(original, writer, () -> { }, id -> { })) {
                Tokens tokens = round.refresh(old); LocalDateTime latest = LocalDateTime.now();
                var claims = Jwts.parser().verifyWith(key()).build().parseSignedClaims(tokens.access()).getPayload();
                assertThat(claims.get("userId", Long.class)).isEqualTo(BASE + 1);
                assertThat(claims.getSubject()).isEqualTo("auth-" + (BASE + 1) + "@fixture.invalid");
                assertThat(claims.get("name", String.class)).isEqualTo("Auth synthetic");
                assertThat(claims.get("role", String.class)).isEqualTo("ROLE_ADMIN");
                assertThat(claims.get("careRegNo", String.class)).isEqualTo("CARE_AUTH");
                assertThat(claims.getExpiration().getTime() - claims.getIssuedAt().getTime()).isBetween(120_000L, 121_000L);
                var renewed = Jwts.parser().verifyWith(key()).build().parseSignedClaims(tokens.refresh()).getPayload();
                assertThat(renewed.keySet()).containsExactlyInAnyOrder("iat", "exp");
                assertThat(renewed.getExpiration().getTime() - renewed.getIssuedAt().getTime()).isBetween(600_000L, 601_000L);
                assertThat(rawToken(BASE + 21).equals(tokens.refresh())).isTrue();
                assertThat(scalar("SELECT created_at::text FROM pawbridge_user.refresh_tokens WHERE refresh_token_id=" + (BASE + 21))).isEqualTo(created);
                LocalDateTime stored = expiry(BASE + 21);
                assertThat(!stored.isBefore(earliest.plusSeconds(600)) && !stored.isAfter(latest.plusSeconds(600))).isTrue();
                assertOnlyRefreshCellsChanged(before, snapshot(), Set.of(BASE + 21), Set.of("token", "expires_at"));
                // The deliberately different source seed TTL guarantees this input changed; no nonce or universal rotation claim.
                assertThat(old.equals(tokens.refresh())).isFalse();
                Throwable replay = catchThrowable(() -> round.refresh(old)); assertBusiness(replay, original, 404, "리프레시 토큰을 찾을 수 없습니다.");
                if (original) { assertThat(round.source.authUpdates.get()).isOne(); assertThat(round.source.authDeletes.get()).isZero(); }
                else { assertThat(round.jdbc.updates).isOne(); assertThat(round.jdbc.deletes).isZero(); }
            } finally { cleanup(); }
        }
    }

    @ParameterizedTest(name = "givenRejectedRefreshInputs__whenCallingTheTransactionalSourceAndCandidate__thenPreservePrecedenceAndNoMutation[{index}]")
    @ValueSource(strings = {"invalid", "missing-token", "expired", "missing-user"})
    void givenRejectedRefreshInputs__whenCallingTheTransactionalSourceAndCandidate__thenPreservePrecedenceAndNoMutation(String scenario) throws Exception {
        for (boolean original : new boolean[] {true, false}) {
            if (!scenario.equals("missing-user")) seedUser(BASE + 1);
            String token = scenario.equals("invalid") ? "not-a-jwt" : issuer(300_000).createRefreshToken();
            if (scenario.equals("expired") || scenario.equals("missing-user"))
                insertRefresh(BASE + 21, BASE + 1, token, LocalDateTime.now().plusSeconds(scenario.equals("expired") ? -3 : 30));
            String before = snapshot();
            // Source schedules remove then throws; requiring DELETE permission here would be an observable adaptation drift.
            if (scenario.equals("expired")) execute("REVOKE DELETE ON pawbridge_user.refresh_tokens FROM " + F3PostgresqlFixture.WRITER);
            try (var round = round(original, writer, () -> { }, id -> { })) {
                Throwable failure = catchThrowable(() -> round.refresh(token));
                int status = scenario.equals("missing-token") ? 404 : scenario.equals("missing-user") ? 400 : 401;
                String message = switch (scenario) { case "invalid" -> "유효하지 않은 토큰입니다."; case "missing-token" -> "리프레시 토큰을 찾을 수 없습니다.";
                    case "expired" -> "리프레시 토큰이 만료되었습니다."; default -> "존재하지 않는 회원입니다."; };
                assertBusiness(failure, original, status, message);
                assertThat(snapshot().equals(before)).isTrue();
                if (original) {
                    assertThat(round.source.authUpdates.get()).isZero(); assertThat(round.source.authDeletes.get()).isZero();
                    assertThat(round.source.authRemoveCalls.get()).isEqualTo(scenario.equals("expired") ? 1 : 0);
                    assertThat(round.source.reads.get()).isEqualTo(scenario.equals("missing-user") ? 1 : 0);
                    assertThat(round.source.authSelects.get()).isEqualTo(scenario.equals("invalid") ? 0 : 1);
                } else {
                    assertThat(round.jdbc.updates).isZero(); assertThat(round.jdbc.deletes).isZero();
                    assertThat(round.jdbc.userReads).isEqualTo(scenario.equals("missing-user") ? 1 : 0);
                    assertThat(round.jdbc.refreshReads).isEqualTo(scenario.equals("invalid") ? 0 : 1);
                }
            } finally {
                if (scenario.equals("expired")) { verifyOwnership(); execute("GRANT DELETE ON pawbridge_user.refresh_tokens TO " + F3PostgresqlFixture.WRITER); }
                cleanup();
            }
        }
    }

    @ParameterizedTest(name = "givenLogoutSelectionAndAnIndependentLaterInsert__whenDeletingByUser__thenRemoveOnlySelectedRowsWithoutReadingUser[{index}]")
    @ValueSource(strings = {"absent", "multiple", "later-insert"})
    void givenLogoutSelectionAndAnIndependentLaterInsert__whenDeletingByUser__thenRemoveOnlySelectedRowsWithoutReadingUser(String scenario) throws Exception {
        for (boolean original : new boolean[] {true, false}) {
            seedUser(BASE + 1); seedUser(BASE + 2);
            if (!scenario.equals("absent")) { seedRefresh(BASE + 21, BASE + 1, 300_000, LocalDateTime.now().plusMinutes(3)); seedRefresh(BASE + 22, BASE + 1, 310_000, LocalDateTime.now().plusMinutes(3)); }
            seedRefresh(BASE + 23, BASE + 2, 320_000, LocalDateTime.now().plusMinutes(3));
            var once = new AtomicBoolean(); Runnable late = () -> {
                if (scenario.equals("later-insert") && once.compareAndSet(false, true)) unchecked(() -> seedRefresh(BASE + 24, BASE + 1, 330_000, LocalDateTime.now().plusMinutes(3)));
            };
            String other = refreshRow(BASE + 23);
            try (var round = round(original, writer, () -> { }, id -> late.run())) {
                round.logout(BASE + 1);
                assertThat(scalar("SELECT count(*) FROM pawbridge_user.refresh_tokens WHERE user_id=" + (BASE + 1))).isEqualTo(scenario.equals("later-insert") ? "1" : "0");
                assertThat(refreshRow(BASE + 23).equals(other)).isTrue();
                if (scenario.equals("later-insert")) assertThat(scalar("SELECT count(*) FROM pawbridge_user.refresh_tokens WHERE refresh_token_id=" + (BASE + 24))).isEqualTo("1");
                if (original) { assertThat(round.source.reads.get()).isZero(); assertThat(round.source.authDeletes.get()).isEqualTo(scenario.equals("absent") ? 0 : 2); }
                else { assertThat(round.jdbc.userReads).isZero(); assertThat(round.jdbc.deletes).isEqualTo(scenario.equals("absent") ? 0 : 2); }
            } finally { cleanup(); }
        }
    }

    @ParameterizedTest(name = "givenAnAlreadyLoadedRefreshDisappearsInAnotherTransaction__whenUpdatingOrDeletingIt__thenBothFailAndRollbackRemainingSelectedRows[{index}]")
    @ValueSource(strings = {"refresh", "logout"})
    void givenAnAlreadyLoadedRefreshDisappearsInAnotherTransaction__whenUpdatingOrDeletingIt__thenBothFailAndRollbackRemainingSelectedRows(String operation) throws Exception {
        for (boolean original : new boolean[] {true, false}) {
            seedUser(BASE + 1);
            String token = seedRefresh(BASE + 21, BASE + 1, 300_000, LocalDateTime.now().plusMinutes(3));
            seedRefresh(BASE + 22, BASE + 1, 310_000, LocalDateTime.now().plusMinutes(3));
            var once = new AtomicBoolean(); var deleted = new long[1];
            LongConsumer remove = id -> { if (once.compareAndSet(false, true)) { deleted[0] = id; unchecked(() -> execute("DELETE FROM pawbridge_user.refresh_tokens WHERE refresh_token_id=" + id)); } };
            Runnable afterRead = () -> { if (operation.equals("refresh")) remove.accept(BASE + 21); };
            String before = snapshot();
            try (var round = round(original, writer, afterRead, operation.equals("logout") ? remove : id -> { })) {
                Throwable failure = catchThrowable(() -> { if (operation.equals("refresh")) round.refresh(token); else round.logout(BASE + 1); });
                assertServerFailure(failure, original); assertThat(deleted[0] != 0).isTrue();
                assertOnlyRefreshRemoved(before, snapshot(), Set.of(deleted[0]));
            } finally { cleanup(); }
        }
    }

    @ParameterizedTest(name = "givenBothRequestsReadTheSameRefreshBeforeEitherWrites__whenRotatingWithoutVersionOrCas__thenBothMayCommitAndTheLastStoredTokenIsOneReturnedValue[{index}]")
    @ValueSource(booleans = {true, false})
    void givenBothRequestsReadTheSameRefreshBeforeEitherWrites__whenRotatingWithoutVersionOrCas__thenBothMayCommitAndTheLastStoredTokenIsOneReturnedValue(boolean original) throws Exception {
        seedUser(BASE + 1); String old = seedRefresh(BASE + 21, BASE + 1, 300_000, LocalDateTime.now().plusMinutes(3)); String before = snapshot();
        var barrier = new CyclicBarrier(2); Runnable meet = () -> { try { barrier.await(8, TimeUnit.SECONDS); } catch (Exception failure) { throw new IllegalStateException("Owned refresh rendezvous failed"); } };
        var workers = Executors.newFixedThreadPool(2);
        try (var parallel = writer("auth-session-race", 2); var round = round(original, parallel, meet, id -> { })) {
            try {
                var a = workers.submit(() -> attempt(round, old)); var b = workers.submit(() -> attempt(round, old));
                Outcome first = a.get(18, TimeUnit.SECONDS), second = b.get(18, TimeUnit.SECONDS);
                assertThat(first.failure() == null && second.failure() == null).as("No artificial single-winner/CAS policy").isTrue();
                String stored = rawToken(BASE + 21);
                assertThat(stored.equals(first.tokens().refresh()) || stored.equals(second.tokens().refresh())).isTrue();
                assertOnlyRefreshCellsChanged(before, snapshot(), Set.of(BASE + 21), Set.of("token", "expires_at"));
            } finally { workers.shutdownNow(); assertThat(workers.awaitTermination(8, TimeUnit.SECONDS)).isTrue(); returned(parallel); }
        } finally { workers.shutdownNow(); }
    }

    @ParameterizedTest(name = "givenExactlyOneRequiredWriterPrivilegeIsRevoked__whenCallingThenRestoring__thenFailServerSideWithoutPartialMutationAndRecover[{index}]")
    @ValueSource(strings = {"email-read", "rotate-write", "logout-delete"})
    void givenExactlyOneRequiredWriterPrivilegeIsRevoked__whenCallingThenRestoring__thenFailServerSideWithoutPartialMutationAndRecover(String denied) throws Exception {
        seedUser(BASE + 1); String token = seedRefresh(BASE + 21, BASE + 1, 300_000, LocalDateTime.now().plusMinutes(3)); String before = snapshot();
        String clause = switch (denied) { case "email-read" -> "SELECT(email) ON pawbridge_user.users"; case "rotate-write" -> "UPDATE(token) ON pawbridge_user.refresh_tokens"; default -> "DELETE ON pawbridge_user.refresh_tokens"; };
        try (var round = round(false, writer, () -> { }, id -> { })) {
            execute("REVOKE " + clause + " FROM " + F3PostgresqlFixture.WRITER);
            try {
                Throwable failure = catchThrowable(() -> { if (denied.equals("logout-delete")) round.logout(BASE + 1); else round.refresh(token); });
                assertThat(sqlState(failure)).isEqualTo("42501"); assertServerFailure(failure, false); assertThat(snapshot().equals(before)).isTrue();
            } finally { verifyOwnership(); execute("GRANT " + clause + " TO " + F3PostgresqlFixture.WRITER); }
            if (denied.equals("logout-delete")) round.logout(BASE + 1); else round.refresh(token);
        }
    }

    @ParameterizedTest(name = "givenAnOwnedDeferredConstraintRejectsTheRefreshUpdate__whenCommitting__thenBothExposeServerFailureAndRestoreTheEntireSnapshot[{index}]")
    @ValueSource(booleans = {true, false})
    void givenAnOwnedDeferredConstraintRejectsTheRefreshUpdate__whenCommitting__thenBothExposeServerFailureAndRestoreTheEntireSnapshot(boolean original) throws Exception {
        seedUser(BASE + 1); String token = seedRefresh(BASE + 21, BASE + 1, 300_000, LocalDateTime.now().plusMinutes(3)); String before = snapshot();
        requireNoCommitArtifacts();
        execute("CREATE FUNCTION pawbridge_user." + COMMIT_REJECTION + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.refresh_token_id=" + (BASE + 21)
                + " THEN RAISE EXCEPTION 'owned auth commit rejection' USING ERRCODE='23514',CONSTRAINT='" + COMMIT_REJECTION + "'; END IF; RETURN NEW; END $$");
        try {
            execute("CREATE CONSTRAINT TRIGGER " + COMMIT_REJECTION + " AFTER UPDATE ON pawbridge_user.refresh_tokens DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION pawbridge_user." + COMMIT_REJECTION + "()");
            try (var round = round(original, writer, () -> { }, id -> { })) {
                Throwable failure = catchThrowable(() -> round.refresh(token)); requireOwnedCommitRejection(failure); assertServerFailure(failure, original);
                assertThat(snapshot().equals(before)).as("Commit failure rolls back token and expiry together").isTrue();
            } finally { verifyOwnership(); execute("DROP TRIGGER " + COMMIT_REJECTION + " ON pawbridge_user.refresh_tokens"); }
        } finally { verifyOwnership(); execute("DROP FUNCTION pawbridge_user." + COMMIT_REJECTION + "()"); }
    }

    private Outcome attempt(Round round, String old) { try { return new Outcome(round.refresh(old), null); } catch (RuntimeException failure) { return new Outcome(null, failure); } }
    private record Outcome(Tokens tokens, RuntimeException failure) { @Override public String toString() { return "Outcome[redacted]"; } }
    private record Tokens(String access, String refresh) { @Override public String toString() { return "Tokens[redacted]"; } }
    private Round round(boolean original, HikariDataSource pool, Runnable afterRead, LongConsumer beforeRemove) { return new Round(original, pool, afterRead, beforeRemove); }
    private final class Round implements AutoCloseable {
        final F13LegacyNicknameOracle source; final ObservingJdbc jdbc; final AuthSessions candidate;
        Round(boolean original, HikariDataSource pool, Runnable afterRead, LongConsumer beforeRemove) {
            if (original) { source = new F13LegacyNicknameOracle(owner, Clock.systemDefaultZone(), PostgresqlAuthSessionCompatibilityTest.this::verifyOwnership);
                source.observeAuth(afterRead, beforeRemove); jdbc = null; candidate = null; }
            else { source = null; jdbc = new ObservingJdbc(pool, afterRead, beforeRemove); var tx = new TransactionTemplate(new JdbcTransactionManager(pool)); tx.setTimeout(15);
                candidate = new JdbcAuthSessions(jdbc, tx, new JwtSessionTokens(key(), ACCESS_MS, REFRESH_MS)); }
        }
        Tokens refresh(String token) { if (source != null) { var value = source.refresh(token); return new Tokens(value.accessToken(), value.refreshToken()); }
            var value = candidate.refresh(token); return new Tokens(value.accessToken(), value.refreshToken()); }
        void logout(long id) { if (source != null) source.logout(id); else candidate.logout(id); }
        @Override public void close() { if (source != null) source.close(); }
    }
    private static final class ObservingJdbc extends JdbcTemplate {
        final Runnable afterRead; final LongConsumer beforeRemove; final AtomicBoolean firstRemove = new AtomicBoolean();
        int refreshReads, userReads, updates, deletes;
        ObservingJdbc(DataSource dataSource, Runnable afterRead, LongConsumer beforeRemove) { super(dataSource); this.afterRead = afterRead; this.beforeRemove = beforeRemove; setQueryTimeout(5); }
        @Override public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) throws DataAccessException {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue(); assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
            List<T> result = super.query(sql, rowMapper, args);
            if (sql.contains("refresh_tokens")) { refreshReads++; if (sql.contains("WHERE token = ?")) afterRead.run(); }
            else if (sql.contains("users")) userReads++;
            return result;
        }
        @Override public int update(PreparedStatementCreator creator) throws DataAccessException { updates++; return super.update(creator); }
        @Override public int update(String sql, Object... args) throws DataAccessException {
            if (sql.startsWith("DELETE FROM pawbridge_user.refresh_tokens")) { if (firstRemove.compareAndSet(false, true)) beforeRemove.accept(((Number) args[0]).longValue()); deletes++; }
            return super.update(sql, args);
        }
    }

    private void assertBusiness(Throwable failure, boolean original, int status, String message) throws Exception {
        assertThat(failure instanceof RuntimeException).isTrue();
        org.springframework.http.ResponseEntity<?> response;
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(com.pawbridge.userservice.exception.common.GlobalExceptionRestAdvice.class);
        var level = logger.getLevel(); logger.setLevel(ch.qos.logback.classic.Level.OFF);
        try {
            if (original) {
                assertThat(failure instanceof com.pawbridge.userservice.exception.common.ApplicationException).isTrue();
                response = new com.pawbridge.userservice.exception.common.GlobalExceptionRestAdvice().applicationException((com.pawbridge.userservice.exception.common.ApplicationException) failure);
            } else if (failure instanceof com.pawbridge.backend.user.internal.session.application.AuthSessionRejectedException rejected)
                response = new AuthSessionExceptionHandler().rejected(rejected);
            else response = new AuthSessionExceptionHandler().missing((com.pawbridge.backend.user.internal.profile.application.UserNotFoundException) failure);
        } finally { logger.setLevel(level); }
        assertEnvelope(response, status, message);
    }
    private void assertServerFailure(Throwable failure, boolean original) throws Exception {
        assertThat(failure instanceof RuntimeException).as("The real operation must fail, without serializing its Throwable").isTrue();
        org.springframework.http.ResponseEntity<?> response;
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(com.pawbridge.userservice.exception.common.GlobalExceptionRestAdvice.class);
        var level = logger.getLevel(); logger.setLevel(ch.qos.logback.classic.Level.OFF);
        try {
            var runtime = (RuntimeException) failure;
            if (original) { var advice = new com.pawbridge.userservice.exception.common.GlobalExceptionRestAdvice(); response = runtime instanceof DataAccessException data ? advice.dbException(data) : advice.serverException(runtime); }
            else response = new AuthSessionExceptionHandler().unexpected(runtime);
        } finally { logger.setLevel(level); }
        assertEnvelope(response, 500, "서버 에러!");
    }
    private void assertEnvelope(org.springframework.http.ResponseEntity<?> response, int status, String message) throws Exception {
        var body = mapper.readTree(mapper.writeValueAsBytes(response.getBody()));
        assertThat(response.getStatusCode().value()).isEqualTo(status); assertThat(body.size()).isEqualTo(3);
        assertThat(body.get("code").asInt()).isEqualTo(status); assertThat(body.get("message").asText()).isEqualTo(message); assertThat(body.get("data").isNull()).isTrue();
    }
    private void assertOnlyRefreshCellsChanged(String before, String after, Set<Long> ids, Set<String> columns) throws Exception {
        var expected = mapper.readTree(before); var actual = mapper.readTree(after);
        for (var row : expected.get("refresh_tokens")) if (ids.contains(row.get("refresh_token_id").asLong()))
            for (String column : columns) ((com.fasterxml.jackson.databind.node.ObjectNode) row).remove(column);
        for (var row : actual.get("refresh_tokens")) if (ids.contains(row.get("refresh_token_id").asLong()))
            for (String column : columns) ((com.fasterxml.jackson.databind.node.ObjectNode) row).remove(column);
        assertThat(actual.equals(expected)).as("Only the selected refresh cells may change; no raw row/token diagnostics").isTrue();
    }
    private void assertOnlyRefreshRemoved(String before, String after, Set<Long> ids) throws Exception {
        var expected = mapper.readTree(before); var rows = (com.fasterxml.jackson.databind.node.ArrayNode) expected.get("refresh_tokens");
        for (int index = rows.size() - 1; index >= 0; index--) if (ids.contains(rows.get(index).get("refresh_token_id").asLong())) rows.remove(index);
        assertThat(mapper.readTree(after).equals(expected)).as("Only the independently deleted selected row disappears; own transaction rolls back").isTrue();
    }
    private HikariDataSource writer(String name, int maximum) {
        HikariConfig config = new HikariConfig(); config.setJdbcUrl(owner.reads().jdbcUrl()); config.setUsername(F3PostgresqlFixture.WRITER); config.setPassword(owner.writerPassword());
        config.setMaximumPoolSize(maximum); config.setMinimumIdle(0); config.setConnectionTimeout(2_000); config.setValidationTimeout(1_000); config.setPoolName(name);
        config.addDataSourceProperty("connectTimeout", "3"); config.addDataSourceProperty("socketTimeout", "12"); config.setConnectionInitSql("SET statement_timeout=8000; SET lock_timeout=5000"); return new HikariDataSource(config);
    }
    private void returned(HikariDataSource pool) {
        if (pool != null) { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); assertThat(TransactionSynchronizationManager.hasResource(pool)).isFalse(); assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero(); }
    }
    private void seedUser(long id) throws Exception {
        assertThat(scalar("SELECT count(*) FROM pawbridge_user.users WHERE user_id=" + id)).isEqualTo("0");
        try (Connection connection = admin(); var statement = connection.prepareStatement("INSERT INTO pawbridge_user.users(user_id,nickname,email,name,provider,role,password,care_reg_no,provider_id,created_at,updated_at) VALUES(?,?,?,'Auth synthetic','GOOGLE','ROLE_ADMIN','AUTH_PRIVATE','CARE_AUTH',?,TIMESTAMP '2025-01-01 18:01:02.123456',TIMESTAMP '2025-02-01 18:01:02.654321')")) {
            statement.setLong(1, id); statement.setString(2, "Auth" + id); statement.setString(3, "auth-" + id + "@fixture.invalid"); statement.setString(4, "auth-provider-" + id);
            assertThat(statement.executeUpdate()).isOne(); users.add(id);
        }
    }
    private String seedRefresh(long id, long user, long ttl, LocalDateTime expires) throws Exception {
        String token = issuer(ttl).createRefreshToken(); insertRefresh(id, user, token, expires); return token;
    }
    private void insertRefresh(long id, long user, String token, LocalDateTime expires) throws Exception {
        assertThat(id >= BASE && id <= BASE + 99 && user >= BASE && user <= BASE + 99).isTrue();
        assertThat(scalar("SELECT count(*) FROM pawbridge_user.refresh_tokens WHERE refresh_token_id=" + id)).isEqualTo("0");
        try (Connection connection = admin(); var statement = connection.prepareStatement("INSERT INTO pawbridge_user.refresh_tokens(refresh_token_id,token,user_id,expires_at,created_at) VALUES(?,?,?,?,TIMESTAMP '2025-01-01 18:01:02.123456')")) {
            statement.setLong(1, id); statement.setString(2, token); statement.setLong(3, user); statement.setTimestamp(4, Timestamp.valueOf(expires), utc());
            assertThat(statement.executeUpdate()).isOne(); refreshRows.put(id, user);
        }
    }
    private void cleanup() throws Exception {
        if (owner == null) return;
        verifyOwnership();
        // Reobserve owned keys after every response/commit error; never delete an unexpected namespace occupant.
        for (var entry : refreshRows.entrySet()) {
            String user = scalar("SELECT user_id::text FROM pawbridge_user.refresh_tokens WHERE refresh_token_id=" + entry.getKey());
            assertThat(user == null || user.equals(entry.getValue().toString())).isTrue();
            execute("DELETE FROM pawbridge_user.refresh_tokens WHERE refresh_token_id=" + entry.getKey() + " AND user_id=" + entry.getValue());
        }
        refreshRows.clear();
        for (long id : users) {
            assertThat(scalar("SELECT (SELECT count(*) FROM pawbridge_user.contact_deletions WHERE user_id=" + id + ")+(SELECT count(*) FROM pawbridge_user.favorites WHERE user_id=" + id
                    + ")+(SELECT count(*) FROM pawbridge_user.refresh_tokens WHERE user_id=" + id + ")+(SELECT count(*) FROM pawbridge_user.shelter_applications WHERE user_id=" + id
                    + ")+(SELECT count(*) FROM pawbridge_community.private_note_members WHERE member_id=" + id + ")")).isEqualTo("0");
            execute("DELETE FROM pawbridge_user.users WHERE user_id=" + id);
        }
        users.clear();
    }
    private JwtProvider issuer(long refreshMs) { return new JwtProvider(F3PostgresqlFixture.required("F3_TEST_JWT_SECRET"), ACCESS_MS, refreshMs); }
    private javax.crypto.SecretKey key() { return Keys.hmacShaKeyFor(F3PostgresqlFixture.required("F3_TEST_JWT_SECRET").getBytes(StandardCharsets.UTF_8)); }
    private String refreshRow(long id) throws Exception { return scalar("SELECT to_jsonb(r)::text FROM pawbridge_user.refresh_tokens r WHERE refresh_token_id=" + id); }
    private String rawToken(long id) throws Exception { return scalar("SELECT token FROM pawbridge_user.refresh_tokens WHERE refresh_token_id=" + id); }
    private LocalDateTime expiry(long id) throws Exception {
        try (Connection connection = admin(); var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT expires_at FROM pawbridge_user.refresh_tokens WHERE refresh_token_id=" + id)) {
            assertThat(rows.next()).isTrue(); return rows.getTimestamp(1, utc()).toLocalDateTime();
        }
    }
    private static Calendar utc() { return Calendar.getInstance(TimeZone.getTimeZone("UTC")); }
    private void execute(String sql) throws Exception { try (Connection connection = admin(); var statement = connection.createStatement()) { statement.execute(sql); } }
    private String scalar(String sql) throws Exception { try (Connection connection = admin(); var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) { if (!rows.next()) return null; return rows.getString(1); } }
    private Connection admin() throws SQLException { verifyOwnership(); return owner.admin(); }
    private interface Checked { void run() throws Exception; }
    private static void unchecked(Checked action) { try { action.run(); } catch (Exception failure) { throw new IllegalStateException("Owned independent auth action failed"); } }
    private void requireNoCommitArtifacts() throws Exception {
        assertThat(scalar("SELECT to_regprocedure('pawbridge_user." + COMMIT_REJECTION + "()') IS NULL AND NOT EXISTS(SELECT 1 FROM pg_trigger WHERE tgname='" + COMMIT_REJECTION + "')")).isEqualTo("t");
    }
    private static void requireOwnedCommitRejection(Throwable failure) {
        var pending = new java.util.ArrayDeque<Throwable>(); var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        if (failure != null) pending.add(failure);
        boolean found = false;
        while (!pending.isEmpty() && seen.size() < 32) {
            Throwable next = pending.removeFirst(); if (!seen.add(next)) continue;
            if (next instanceof PSQLException sql && "23514".equals(sql.getSQLState()) && sql.getServerErrorMessage() != null && COMMIT_REJECTION.equals(sql.getServerErrorMessage().getConstraint())) found = true;
            if (next.getCause() != null) pending.add(next.getCause());
            if (next instanceof SQLException sql && sql.getNextException() != null) pending.add(sql.getNextException());
        }
        assertThat(found).as("Actual owned deferred rejection must reach each implementation's commit").isTrue();
    }
    private static String sqlState(Throwable failure) {
        var pending = new java.util.ArrayDeque<Throwable>(); var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        if (failure != null) pending.add(failure);
        while (!pending.isEmpty() && seen.size() < 32) {
            Throwable next = pending.removeFirst(); if (!seen.add(next)) continue;
            if (next instanceof SQLException sql && sql.getSQLState() != null) return sql.getSQLState();
            if (next.getCause() != null) pending.add(next.getCause());
            if (next instanceof SQLException sql && sql.getNextException() != null) pending.add(sql.getNextException());
        }
        return null;
    }

    private String snapshot() throws Exception {
        String[][] tables = {
            {"private_notes","pawbridge_community.private_notes","note_id"},{"mailboxes","pawbridge_community.private_note_mailboxes","member_id,note_id"},
            {"pending","pawbridge_user.contact_deletions","user_id"},{"members","pawbridge_community.private_note_members","member_id"},{"blocks","pawbridge_community.private_note_blocks","member_id,blocked_id"},
            {"visits","pawbridge_community.daily_visit_count","visit_date"},{"videos","pawbridge_community.home_videos","id"},{"board","pawbridge_community.home_video_board","id"},
            {"users","pawbridge_user.users","user_id"},{"favorites","pawbridge_user.favorites","favorite_id"},{"posts","pawbridge_community.posts","post_id"},{"comments","pawbridge_community.comments","comment_id"},
            {"f4comments","pawbridge_f4_fixture.comments","comment_id"},{"notes","pawbridge_f4_fixture.private_notes","note_id"},{"outbox","pawbridge_community.outbox_events","outbox_id"},{"search","pawbridge_community.post_search_documents","post_id"},
            {"private_note_requests","pawbridge_community.private_note_requests","sender_id,request_id"},{"animal_reports","pawbridge_community.animal_reports","report_id"},{"refresh_tokens","pawbridge_user.refresh_tokens","refresh_token_id"},{"shelter_applications","pawbridge_user.shelter_applications","id"},
            {"chat_rooms","pawbridge_community.member_chat_rooms","room_id"},{"chat_members","pawbridge_community.member_chat_room_members","room_id,member_id"},{"chat_messages","pawbridge_community.member_chat_messages","room_id,sequence"},{"chat_requests","pawbridge_community.member_chat_requests","sender_id,request_id"}};
        var sql = new StringBuilder("SELECT json_build_object(");
        for(int i=0;i<tables.length;i++){if(i>0)sql.append(',');var t=tables[i];sql.append("'").append(t[0]).append("',(SELECT COALESCE(json_agg(r ORDER BY ").append(t[2]).append("),'[]'::json) FROM ").append(t[1]).append(" r)");}
        sql.append(")::text");String result = scalar(sql.toString());assertThat(mapper.readTree(result).size()).isEqualTo(24);return result;
    }

    private void verifyOwnership() {
        try {
            String run = F3PostgresqlFixture.required("GITHUB_RUN_ID") + "-" + F3PostgresqlFixture.required("GITHUB_RUN_ATTEMPT");
            Path extra = receipt("admin.user.update.fixture.receipt", "admin-user-update-fixture-write.txt");
            Path prior = receipt("member.cleanup.fixture.receipt", "member-cleanup-fixture-write.txt");
            assertThat(extra.getParent()).isEqualTo(prior.getParent());
            String grants = "GRANT SELECT(role,care_reg_no),UPDATE(role,care_reg_no) ON pawbridge_user.users TO pawbridge_f3_writer;";
            String expected = "owner="+run+"\nsource_sha=c32ae0d84b69969abd35c541dbdd4e81738c4be5\nfixture=member cleanup plus administrator User update\nnew_business_ddl=0\nnew_marker=0\nsnapshot_tables=24\nreader_delta=0\nnew_sequence_acl=0\nwriter_user_select=user_id,nickname,role,care_reg_no\nwriter_user_update=user_id,nickname,updated_at,role,care_reg_no\n"+sha(grants.getBytes(java.nio.charset.StandardCharsets.UTF_8))+"  admin-user-exact-grants\n";
            assertThat(Files.readString(extra).equals(expected)).isTrue();
            assertThat(Files.readString(prior).equals("owner="+run+"\n"+MEMBER_PROOF)).isTrue();
            Path oracle = receipt("admin.user.update.oracle.receipt", "f13-admin-update-oracle-sha256.txt");assertThat(oracle.getParent()).isEqualTo(extra.getParent());assertThat(sha(Files.readAllBytes(oracle))).isEqualTo(ORIGINAL_RECEIPT_SHA);
            Path auth = receipt("auth.sessions.fixture.receipt", "auth-sessions-fixture-write.txt");
            assertThat(auth.getParent()).isEqualTo(extra.getParent());
            assertThat(Files.readString(auth).equals("owner=" + run + "\n" + AUTH_PROOF)).isTrue();
            Path authOracle = receipt("auth.sessions.oracle.receipt", "f13-auth-sessions-oracle-sha256.txt");
            assertThat(authOracle.getParent()).isEqualTo(extra.getParent());
            assertThat(sha(Files.readAllBytes(authOracle))).isEqualTo(AUTH_ORACLE_SHA);
            Path app = auth.getParent().getParent().getParent();
            for (String line : Files.readAllLines(authOracle)) {
                String marker = line.contains("  generated ") ? "  generated " : line.contains("  shared-byte-identity ") ? "  shared-byte-identity " : null;
                if (marker == null) continue;
                String[] parts = line.split(marker, 2);
                Path artifact = marker.equals("  generated ") ? app.resolve("boot/build/generated/sources/f13LegacyOracle/java").resolve(parts[1]) : app.getParent().resolve(parts[1]);
                assertThat(Files.isSymbolicLink(artifact)).isFalse(); assertThat(artifact.toRealPath()).isEqualTo(artifact);
                assertThat(sha(Files.readAllBytes(artifact))).isEqualTo(parts[0]);
            }
            try(Connection c=owner.admin();var s=c.createStatement();var r=s.executeQuery("SELECT value||':'||owner FROM pawbridge_f13_fixture.marker")){assertThat(r.next()).isTrue();assertThat(r.getString(1)).isEqualTo("pawbridge-f13-ci-only-v1:"+run);}
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException | SQLException failure) { throw new IllegalStateException("Owned auth-session evidence cannot be verified"); }
    }
    private static Path receipt(String property,String filename)throws java.io.IOException{Path p=Path.of(System.getProperty(property,"")).toAbsolutePath().normalize();assertThat(p.getFileName().toString()).isEqualTo(filename);assertThat(Files.isSymbolicLink(p)).isFalse();assertThat(Files.isRegularFile(p)).isTrue();assertThat(p.toRealPath()).isEqualTo(p);return p;}
    private static String sha(byte[] bytes)throws java.security.NoSuchAlgorithmException{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
}
