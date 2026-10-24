package com.pawbridge.backend.compatibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.user.internal.session.application.AuthSessionRejectedException;
import com.pawbridge.backend.user.internal.session.application.AuthSessionRejectedException.Reason;
import com.pawbridge.backend.user.internal.session.application.AuthSessions;
import com.pawbridge.backend.user.internal.session.application.IssuedRefreshToken;
import com.pawbridge.backend.user.internal.session.infrastructure.AuthSessionPolicy;
import com.pawbridge.backend.user.internal.session.infrastructure.JdbcAuthSessionStore;
import com.pawbridge.backend.user.internal.session.infrastructure.JdbcAuthSessions;
import com.pawbridge.backend.user.internal.session.infrastructure.JwtSessionTokens;
import com.pawbridge.backend.user.internal.session.infrastructure.RefreshTokens;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Cookie refresh sessions against the owned PostgreSQL fixture with only the exact writer column rights from
 * f29_ci.py auth-sessions-prepare. The legacy JWT refresh source is intentionally not compared any more: the
 * rotation, reuse and eviction rules are new, so the database semantics themselves are asserted here.
 * Token values and hashes are only compared, never printed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresqlAuthSessionCompatibilityTest {
    private static final long BASE = 9_985_800;
    private static final long ACCESS_MS = 120_999;
    private static final long REFRESH_MS = 600_999;
    private static final String ORIGINAL_RECEIPT_SHA = "96e0d308622eedc6d41415fc6618af2114ade873cad7c50ca505690dcf4c4891";
    private static final String MEMBER_PROOF="source_sha=f06a72a06bd26662c647bde780dfc7a27bf7349c\nfixture=F29 plus User deletion and installed-chat cleanup\nnew_business_ddl=0\nsynthetic_tables_added=6\nsynthetic_identity_sequences_added=2\nnew_schema=0\nnew_marker=0\nsnapshot_tables=24\nreader_delta=0\nnew_sequence_acl=0\ncf93ed908b18f63f59832cd0fe5bcf500ccf9e4c39972deeda5de446c4dd953d  member-ddl-fragment-0\neb3fe612fe9d4aff8372539b0d59e1da4c34266036ce88f1ed0ba51b88f5e139  member-ddl-fragment-1\nb705222e7a9c9a6964628c937cff63470e1dd30103b27111ae3c5d94d284e1ec  member-ddl-fragment-2\n7afb8a3d992eba1258d61a610a418133f56766e9def3eb2e105c584bedcd2fdb  member-ddl-fragment-3\n5604c03964f051343785c2d30f9bf20022d6a0c867152b6175168c4f5ea81754  member-exact-grants\n";
    private static final String AUTH_ORACLE_SHA = "37f8491027f62e11e85f08fadd7afa2cfc5c8bc0cc93753935c8b96f5d67a58f";
    private static final String SESSION_COLUMNS = "session_id,user_id,token_hash,rotated_at,created_at,"
            + "last_used_at,expires_at,absolute_expires_at,revoked_at";
    private static final String AUTH_PROOF = "source_sha=f06a72a06bd26662c647bde780dfc7a27bf7349c\n"
            + "fixture=administrator User update plus refresh session tables and writer\n"
            + "new_business_ddl=1\nnew_marker=0\nsnapshot_tables=24\nreader_delta=0\nnew_sequence_acl=0\n"
            + "writer_user_select=user_id,nickname,role,care_reg_no,email,name\n"
            + "writer_session_select=" + SESSION_COLUMNS + "\n"
            + "writer_session_insert=user_id,token_hash,created_at,last_used_at,expires_at,absolute_expires_at\n"
            + "writer_session_update=token_hash,rotated_at,last_used_at,expires_at,revoked_at\n"
            + "writer_session_delete=1\n"
            + "writer_history_select=token_hash,session_id,rotated_at\n"
            + "writer_history_insert=token_hash,session_id,rotated_at\n"
            + "deaebdf721705d2cfe17fb06abab2d75b41c014924ce6426ede205a10021b8c3  auth-session-ddl\n"
            + "cf57839d700f931c7225073b0aacd58b9da6a104a3f1f9659a363db75987804c  auth-session-exact-grants\n";

    private final ObjectMapper mapper = new ObjectMapper();
    private final Set<Long> users = new LinkedHashSet<>();
    private F3PostgresqlFixture owner;
    private HikariDataSource writer;
    private String baseline;

    @BeforeAll
    void openOnlyTheOwnedFixtureWithTheNewSessionTable() throws Exception {
        assertThat(ZoneId.systemDefault().getId()).isIn("UTC", "Asia/Seoul");
        owner = F3PostgresqlFixture.open();
        verifyOwnership();
        assertThat(scalar("SELECT count(*) FROM pawbridge_user.users WHERE user_id BETWEEN " + BASE + " AND " + (BASE + 99))).isEqualTo("0");
        assertThat(scalar("SELECT count(*) FROM pawbridge_user.auth_sessions")).isEqualTo("0");
        writer = writer("auth-session-writer", 1);
        baseline = snapshot();
    }

    @AfterEach
    void removeOnlyOwnedRowsAndCheckAllTwentyFourTables() throws Exception {
        try {
            cleanup();
        } finally {
            returned(writer);
        }
        if (baseline != null) {
            assertThat(snapshot().equals(baseline)).as("All twenty-four raw tables restore without logging token values").isTrue();
        }
        assertThat(scalar("SELECT count(*) FROM pawbridge_user.auth_sessions")).isEqualTo("0");
    }

    @AfterAll
    void closeWriter() {
        if (writer != null) {
            writer.close();
        }
    }

    @Test
    void givenCurrentClaimsAndAnActiveSession__whenRotating__thenIssueCurrentClaimsAndRecordTheRetiredHash() throws Exception {
        seedUser(BASE + 1);
        seedUser(BASE + 2);
        var service = new Service(writer);
        IssuedRefreshToken first = service.login(BASE + 1);
        IssuedRefreshToken other = service.login(BASE + 2);
        assertThat(first.maxAgeSeconds()).isBetween(REFRESH_MS / 1000 - 1, REFRESH_MS / 1000);
        String created = scalar("SELECT created_at::text FROM pawbridge_user.auth_sessions WHERE user_id=" + (BASE + 1));
        String untouched = sessionRows(BASE + 2);

        var rotated = (AuthSessions.Rotated) service.sessions.refresh(first.value());

        var claims = Jwts.parser().verifyWith(key()).build().parseSignedClaims(rotated.accessToken()).getPayload();
        assertThat(claims.get("userId", Long.class)).isEqualTo(BASE + 1);
        assertThat(claims.getSubject()).isEqualTo("auth-" + (BASE + 1) + "@fixture.invalid");
        assertThat(claims.get("name", String.class)).isEqualTo("Auth synthetic");
        assertThat(claims.get("role", String.class)).isEqualTo("ROLE_ADMIN");
        assertThat(claims.get("careRegNo", String.class)).isEqualTo("CARE_AUTH");
        assertThat(claims.getExpiration().getTime() - claims.getIssuedAt().getTime()).isBetween(120_000L, 121_000L);
        String next = rotated.refreshToken().value();
        assertThat(RefreshTokens.wellFormed(next)).isTrue();
        assertThat(next.equals(first.value())).isFalse();
        assertThat(rotated.refreshToken().maxAgeSeconds()).isBetween(REFRESH_MS / 1000 - 1, REFRESH_MS / 1000);
        String where = " FROM pawbridge_user.auth_sessions WHERE user_id=" + (BASE + 1);
        assertThat(scalar("SELECT (token_hash=" + literal(RefreshTokens.hash(next))
                + " AND rotated_at = last_used_at AND revoked_at IS NULL)::int" + where)).isEqualTo("1");
        assertThat(scalar("SELECT created_at::text" + where)).isEqualTo(created);
        assertThat(scalar("SELECT count(*) FROM pawbridge_user.auth_session_token_history h JOIN pawbridge_user.auth_sessions s"
                + " ON s.session_id = h.session_id WHERE h.token_hash=" + literal(RefreshTokens.hash(first.value()))
                + " AND h.rotated_at = s.rotated_at")).as("The retired hash is kept with the rotation time").isEqualTo("1");
        assertThat(scalar("SELECT (SELECT count(*) FROM pawbridge_user.auth_sessions WHERE token_hash IN (" + literal(first.value())
                + "," + literal(next) + "))+(SELECT count(*) FROM pawbridge_user.auth_session_token_history WHERE token_hash="
                + literal(first.value()) + ")")).as("Raw tokens are never stored").isEqualTo("0");
        assertThat(service.sessions.refresh(next)).isInstanceOf(AuthSessions.Rotated.class);
        assertThat(sessionRows(BASE + 2).equals(untouched)).isTrue();
        assertThat(other.value().equals(next)).isFalse();
    }

    @ParameterizedTest(name = "givenInactiveOrUnknownTokens__whenRefreshing__thenRejectWithoutAnyMutation[{index}]")
    @ValueSource(strings = {"idle-expired", "absolute-expired", "revoked", "unknown"})
    void givenInactiveOrUnknownTokens__whenRefreshing__thenRejectWithoutAnyMutation(String scenario) throws Exception {
        seedUser(BASE + 1);
        var service = new Service(writer);
        IssuedRefreshToken session = service.login(BASE + 1);
        String column = switch (scenario) {
            case "idle-expired" -> "expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'";
            case "absolute-expired" -> "absolute_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'";
            case "revoked" -> "revoked_at = CURRENT_TIMESTAMP";
            default -> null;
        };
        if (column != null) {
            execute("UPDATE pawbridge_user.auth_sessions SET " + column + " WHERE user_id=" + (BASE + 1));
        }
        String presented = scenario.equals("unknown") ? new RefreshTokens().generate() : session.value();
        String before = sessionRows(BASE + 1);

        Throwable failure = catchThrowable(() -> service.sessions.refresh(presented));

        assertThat(failure).isInstanceOf(AuthSessionRejectedException.class);
        assertThat(((AuthSessionRejectedException) failure).reason()).isEqualTo(Reason.INVALID);
        assertThat(sessionRows(BASE + 1).equals(before)).as("A rejected refresh changes no session").isTrue();
    }

    @Test
    void givenATwoGenerationsOldTokenAfterTheGraceWindow__whenRefreshing__thenRevokeOnlyThatSessionAndKeepTheRevocation() throws Exception {
        seedUser(BASE + 1);
        var service = new Service(writer);
        IssuedRefreshToken stolen = service.login(BASE + 1);
        IssuedRefreshToken otherDevice = service.login(BASE + 1);
        var second = (AuthSessions.Rotated) service.sessions.refresh(stolen.value());
        var third = (AuthSessions.Rotated) service.sessions.refresh(second.refreshToken().value());
        long sessionId = Long.parseLong(scalar("SELECT session_id FROM pawbridge_user.auth_sessions WHERE token_hash="
                + literal(RefreshTokens.hash(third.refreshToken().value()))));
        moveRotationsBack(sessionId, 120);
        String otherBefore = sessionRow(otherDevice.value());

        Throwable reuse = catchThrowable(() -> service.sessions.refresh(stolen.value()));

        assertThat(((AuthSessionRejectedException) reuse).reason()).isEqualTo(Reason.REUSED);
        assertThat(scalar("SELECT (revoked_at IS NOT NULL)::int FROM pawbridge_user.auth_sessions WHERE session_id=" + sessionId))
                .as("The revocation committed").isEqualTo("1");
        assertThat(sessionRow(otherDevice.value()).equals(otherBefore)).as("Other sessions of the user stay").isTrue();
        Throwable latest = catchThrowable(() -> service.sessions.refresh(third.refreshToken().value()));
        assertThat(((AuthSessionRejectedException) latest).reason()).isEqualTo(Reason.INVALID);
        assertThat(service.sessions.refresh(otherDevice.value())).isInstanceOf(AuthSessions.Rotated.class);
    }

    @Test
    void givenALostRotationResponse__whenTheRetiredTokenReturnsAfter59Seconds__thenRotateAgainOnceAndRecordTheUnusedSuccessor() throws Exception {
        seedUser(BASE + 1);
        var service = new Service(writer);
        IssuedRefreshToken kept = service.login(BASE + 1);
        var lost = (AuthSessions.Rotated) service.sessions.refresh(kept.value());
        long sessionId = Long.parseLong(scalar("SELECT session_id FROM pawbridge_user.auth_sessions WHERE token_hash="
                + literal(RefreshTokens.hash(lost.refreshToken().value()))));
        // Keep the retired token inside the 60-second grace window with room for the connections the
        // checks below open; the last refresh of the same token must still see it as recent.
        moveRotationsBack(sessionId, 50);

        var recovered = (AuthSessions.Rotated) service.sessions.refresh(kept.value());

        String current = recovered.refreshToken().value();
        assertThat(scalar("SELECT (token_hash=" + literal(RefreshTokens.hash(current)) + ")::int FROM pawbridge_user.auth_sessions"
                + " WHERE session_id=" + sessionId)).isEqualTo("1");
        assertThat(scalar("SELECT count(*) FROM pawbridge_user.auth_session_token_history WHERE session_id=" + sessionId
                + " AND token_hash IN (" + literal(RefreshTokens.hash(kept.value())) + "," + literal(RefreshTokens.hash(lost.refreshToken().value()))
                + ")")).isEqualTo("2");
        String afterRecovery = sessionRows(BASE + 1);
        assertThat(service.sessions.refresh(kept.value())).as("Only the latest retirement rotates again")
                .isInstanceOf(AuthSessions.AccessOnly.class);
        assertThat(sessionRows(BASE + 1).equals(afterRecovery)).isTrue();
        assertThat(service.sessions.refresh(current)).isInstanceOf(AuthSessions.Rotated.class);
    }

    @Test
    void givenTwoGraceRequestsWithTheSameRetiredToken__whenRefreshingConcurrently__thenOnlyOneRotatesAgain() throws Exception {
        seedUser(BASE + 1);
        var setup = new Service(writer);
        IssuedRefreshToken retired = setup.login(BASE + 1);
        setup.sessions.refresh(retired.value());
        var barrier = new CyclicBarrier(2);
        var workers = Executors.newFixedThreadPool(2);
        try (var parallel = writer("auth-session-race", 2)) {
            var service = new Service(parallel);
            Callable<Object> attempt = () -> {
                barrier.await(8, TimeUnit.SECONDS);
                try {
                    return service.sessions.refresh(retired.value());
                } catch (RuntimeException failure) {
                    return failure;
                }
            };
            try {
                var first = workers.submit(attempt);
                var second = workers.submit(attempt);
                List<Object> outcomes = List.of(first.get(18, TimeUnit.SECONDS), second.get(18, TimeUnit.SECONDS));
                long rotatedCount = outcomes.stream().filter(AuthSessions.Rotated.class::isInstance).count();
                long accessOnlyCount = outcomes.stream().filter(AuthSessions.AccessOnly.class::isInstance).count();
                assertThat(rotatedCount).as("Exactly one grace request rotates again").isEqualTo(1);
                assertThat(accessOnlyCount).as("The other receives an access token only").isEqualTo(1);
                var winner = (AuthSessions.Rotated) outcomes.stream().filter(AuthSessions.Rotated.class::isInstance).findFirst().orElseThrow();
                assertThat(scalar("SELECT (token_hash=" + literal(RefreshTokens.hash(winner.refreshToken().value()))
                        + " AND revoked_at IS NULL)::int FROM pawbridge_user.auth_sessions WHERE user_id=" + (BASE + 1))).isEqualTo("1");
            } finally {
                workers.shutdownNow();
                assertThat(workers.awaitTermination(8, TimeUnit.SECONDS)).isTrue();
                returned(parallel);
            }
        }
    }

    @Test
    void givenSixLoginsOfOneUser__whenCreatingSessions__thenKeepOnlyTheFiveMostRecentlyUsedActive() throws Exception {
        seedUser(BASE + 1);
        var service = new Service(writer);
        List<IssuedRefreshToken> logins = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            logins.add(service.login(BASE + 1));
        }
        // Using the first session makes the second one the least recently used.
        var refreshed = (AuthSessions.Rotated) service.sessions.refresh(logins.getFirst().value());

        logins.add(service.login(BASE + 1));

        assertThat(scalar("SELECT count(*) FROM pawbridge_user.auth_sessions WHERE user_id=" + (BASE + 1)
                + " AND revoked_at IS NULL")).isEqualTo("5");
        assertThat(scalar("SELECT (revoked_at IS NOT NULL)::int FROM pawbridge_user.auth_sessions WHERE token_hash="
                + literal(RefreshTokens.hash(logins.get(1).value())))).isEqualTo("1");
        assertThat(scalar("SELECT (revoked_at IS NULL)::int FROM pawbridge_user.auth_sessions WHERE token_hash="
                + literal(RefreshTokens.hash(refreshed.refreshToken().value())))).isEqualTo("1");
        assertThat(((AuthSessionRejectedException) catchThrowable(() -> service.sessions.refresh(logins.get(1).value()))).reason())
                .isEqualTo(Reason.INVALID);
    }

    @ParameterizedTest(name = "givenLogoutRevokeAllOrCleanup__whenWriting__thenChangeOnlyTheTargetedSessions[{index}]")
    @ValueSource(strings = {"logout", "revoke-all", "cleanup"})
    void givenLogoutRevokeAllOrCleanup__whenWriting__thenChangeOnlyTheTargetedSessions(String operation) throws Exception {
        seedUser(BASE + 1);
        seedUser(BASE + 2);
        var service = new Service(writer);
        IssuedRefreshToken target = service.login(BASE + 1);
        IssuedRefreshToken sibling = service.login(BASE + 1);
        IssuedRefreshToken stranger = service.login(BASE + 2);
        String strangerBefore = sessionRows(BASE + 2);
        switch (operation) {
            case "logout" -> {
                String siblingBefore = sessionRow(sibling.value());
                service.sessions.logout(target.value());
                String afterFirst = sessionRows(BASE + 1);
                service.sessions.logout(target.value());
                service.sessions.logout(null);
                assertThat(sessionRows(BASE + 1).equals(afterFirst)).as("Logout is idempotent").isTrue();
                assertThat(scalar("SELECT (revoked_at IS NOT NULL)::int FROM pawbridge_user.auth_sessions WHERE token_hash="
                        + literal(RefreshTokens.hash(target.value())))).isEqualTo("1");
                assertThat(sessionRow(sibling.value()).equals(siblingBefore)).isTrue();
            }
            case "revoke-all" -> {
                service.transaction.executeWithoutResult(status -> service.store.revokeAll(BASE + 1));
                assertThat(scalar("SELECT count(*) FROM pawbridge_user.auth_sessions WHERE user_id=" + (BASE + 1)
                        + " AND revoked_at IS NULL")).isEqualTo("0");
            }
            default -> {
                IssuedRefreshToken idle = service.login(BASE + 1);
                IssuedRefreshToken absolute = service.login(BASE + 1);
                execute("UPDATE pawbridge_user.auth_sessions SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE token_hash="
                        + literal(RefreshTokens.hash(idle.value())));
                execute("UPDATE pawbridge_user.auth_sessions SET absolute_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE token_hash="
                        + literal(RefreshTokens.hash(absolute.value())));
                execute("UPDATE pawbridge_user.auth_sessions SET revoked_at = CURRENT_TIMESTAMP - INTERVAL '2 days' WHERE token_hash="
                        + literal(RefreshTokens.hash(target.value())));
                execute("UPDATE pawbridge_user.auth_sessions SET revoked_at = CURRENT_TIMESTAMP - INTERVAL '1 hour' WHERE token_hash="
                        + literal(RefreshTokens.hash(sibling.value())));
                Integer deleted = service.transaction.execute(status -> service.store.deleteFinished(1000));
                assertThat(deleted).isEqualTo(3);
                assertThat(scalar("SELECT count(*) FROM pawbridge_user.auth_session_token_history h WHERE NOT EXISTS"
                        + " (SELECT 1 FROM pawbridge_user.auth_sessions s WHERE s.session_id = h.session_id)")).isEqualTo("0");
                assertThat(scalar("SELECT count(*) FROM pawbridge_user.auth_sessions WHERE token_hash="
                        + literal(RefreshTokens.hash(sibling.value())))).as("Recently revoked rows are kept for a day").isEqualTo("1");
            }
        }
        assertThat(sessionRows(BASE + 2).equals(strangerBefore)).as("Another user's sessions stay").isTrue();
        assertThat(stranger.value().equals(target.value())).isFalse();
    }

    @ParameterizedTest(name = "givenExactlyOneRequiredWriterPrivilegeIsRevoked__whenCalling__thenFailServerSideWithoutPartialMutationAndRecover[{index}]")
    @ValueSource(strings = {"email-read", "rotate-write", "history-write", "cleanup-delete"})
    void givenExactlyOneRequiredWriterPrivilegeIsRevoked__whenCalling__thenFailServerSideWithoutPartialMutationAndRecover(String denied) throws Exception {
        seedUser(BASE + 1);
        var service = new Service(writer);
        IssuedRefreshToken session = service.login(BASE + 1);
        if (denied.equals("cleanup-delete")) {
            // Gives the cleanup exactly one finished row to delete.
            execute("UPDATE pawbridge_user.auth_sessions SET revoked_at = CURRENT_TIMESTAMP - INTERVAL '2 days' WHERE user_id=" + (BASE + 1));
        }
        String clause = switch (denied) {
            case "email-read" -> "SELECT(email) ON pawbridge_user.users";
            case "rotate-write" -> "UPDATE(token_hash) ON pawbridge_user.auth_sessions";
            case "history-write" -> "INSERT(token_hash) ON pawbridge_user.auth_session_token_history";
            default -> "DELETE ON pawbridge_user.auth_sessions";
        };
        String before = sessionRows(BASE + 1);
        execute("REVOKE " + clause + " FROM " + F3PostgresqlFixture.WRITER);
        try {
            Throwable failure = catchThrowable(() -> {
                if (denied.equals("cleanup-delete")) {
                    service.transaction.execute(status -> service.store.deleteFinished(1000));
                } else {
                    service.sessions.refresh(session.value());
                }
            });
            assertThat(sqlState(failure)).isEqualTo("42501");
            assertThat(sessionRows(BASE + 1).equals(before)).as("The whole transaction rolls back").isTrue();
        } finally {
            verifyOwnership();
            execute("GRANT " + clause + " TO " + F3PostgresqlFixture.WRITER);
        }
        if (denied.equals("cleanup-delete")) {
            Integer deleted = service.transaction.execute(status -> service.store.deleteFinished(1000));
            assertThat(deleted).isEqualTo(1);
        } else {
            assertThat(service.sessions.refresh(session.value())).isInstanceOf(AuthSessions.Rotated.class);
        }
    }

    @Test
    void givenADeletedUser__whenDeletingTheAccountRow__thenItsSessionsCascade() throws Exception {
        seedUser(BASE + 1);
        seedUser(BASE + 2);
        var service = new Service(writer);
        service.login(BASE + 1);
        service.login(BASE + 1);
        service.login(BASE + 2);
        String strangerBefore = sessionRows(BASE + 2);

        int deleted = new JdbcTemplate(writer).update("DELETE FROM pawbridge_user.users WHERE user_id=?", BASE + 1);

        assertThat(deleted).isOne();
        users.remove(BASE + 1);
        assertThat(scalar("SELECT count(*) FROM pawbridge_user.auth_sessions WHERE user_id=" + (BASE + 1))).isEqualTo("0");
        assertThat(sessionRows(BASE + 2).equals(strangerBefore)).isTrue();
    }

    /** The production wiring with the fixture's writer: one pool, one transaction manager, the real policy shape. */
    private final class Service {
        final JdbcAuthSessionStore store;
        final AuthSessions sessions;
        final TransactionTemplate transaction;

        Service(HikariDataSource pool) {
            var jdbc = new JdbcTemplate(pool);
            jdbc.setQueryTimeout(5);
            transaction = new TransactionTemplate(new JdbcTransactionManager(pool));
            transaction.setTimeout(15);
            var policy = new AuthSessionPolicy(Duration.ofMillis(REFRESH_MS), Duration.ofDays(30), 5, Duration.ofSeconds(60));
            var refreshTokens = new RefreshTokens();
            store = new JdbcAuthSessionStore(jdbc, policy, refreshTokens);
            sessions = new JdbcAuthSessions(jdbc, transaction, new JwtSessionTokens(key(), ACCESS_MS), store, refreshTokens);
        }

        IssuedRefreshToken login(long userId) {
            assertThat(users.contains(userId)).isTrue();
            return transaction.execute(status -> store.create(userId));
        }
    }

    /** Sessions of the user and their token history, so a check also catches an unexpected history write. */
    private String sessionRows(long userId) throws Exception {
        return scalar("SELECT json_build_object("
                + "'sessions',(SELECT COALESCE(json_agg(s ORDER BY session_id),'[]'::json) FROM pawbridge_user.auth_sessions s WHERE user_id=" + userId + "),"
                + "'history',(SELECT COALESCE(json_agg(h ORDER BY h.token_hash),'[]'::json) FROM pawbridge_user.auth_session_token_history h"
                + " JOIN pawbridge_user.auth_sessions s USING (session_id) WHERE s.user_id=" + userId + "))::text");
    }

    /** Moves every rotation of the session back in time, keeping the session and its history consistent. */
    private void moveRotationsBack(long sessionId, int seconds) throws Exception {
        execute("UPDATE pawbridge_user.auth_session_token_history SET rotated_at = rotated_at - INTERVAL '" + seconds
                + " seconds' WHERE session_id=" + sessionId);
        execute("UPDATE pawbridge_user.auth_sessions SET rotated_at = rotated_at - INTERVAL '" + seconds
                + " seconds', last_used_at = last_used_at - INTERVAL '" + seconds + " seconds' WHERE session_id=" + sessionId);
    }

    private String sessionRow(String token) throws Exception {
        return scalar("SELECT to_jsonb(s)::text FROM pawbridge_user.auth_sessions s WHERE token_hash=" + literal(RefreshTokens.hash(token)));
    }

    private static String literal(String value) {
        assertThat(value.matches("[A-Za-z0-9_-]+")).isTrue();
        return "'" + value + "'";
    }

    private void cleanup() throws Exception {
        if (owner == null) {
            return;
        }
        verifyOwnership();
        for (long id : users) {
            execute("DELETE FROM pawbridge_user.auth_sessions WHERE user_id=" + id);
            assertThat(scalar("SELECT (SELECT count(*) FROM pawbridge_user.contact_deletions WHERE user_id=" + id + ")+(SELECT count(*) FROM pawbridge_user.favorites WHERE user_id=" + id
                    + ")+(SELECT count(*) FROM pawbridge_user.refresh_tokens WHERE user_id=" + id + ")+(SELECT count(*) FROM pawbridge_user.shelter_applications WHERE user_id=" + id
                    + ")+(SELECT count(*) FROM pawbridge_community.private_note_members WHERE member_id=" + id + ")")).isEqualTo("0");
            execute("DELETE FROM pawbridge_user.users WHERE user_id=" + id);
        }
        users.clear();
    }

    private javax.crypto.SecretKey key() {
        return Keys.hmacShaKeyFor(F3PostgresqlFixture.required("F3_TEST_JWT_SECRET").getBytes(StandardCharsets.UTF_8));
    }

    private void execute(String sql) throws Exception {
        try (Connection connection = admin(); var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private String scalar(String sql) throws Exception {
        try (Connection connection = admin(); var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            if (!rows.next()) {
                return null;
            }
            return rows.getString(1);
        }
    }

    private Connection admin() throws SQLException {
        verifyOwnership();
        return owner.admin();
    }

    private static String sqlState(Throwable failure) {
        var pending = new java.util.ArrayDeque<Throwable>();
        var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        if (failure != null) {
            pending.add(failure);
        }
        while (!pending.isEmpty() && seen.size() < 32) {
            Throwable next = pending.removeFirst();
            if (!seen.add(next)) {
                continue;
            }
            if (next instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
            if (next.getCause() != null) {
                pending.add(next.getCause());
            }
            if (next instanceof SQLException sql && sql.getNextException() != null) {
                pending.add(sql.getNextException());
            }
        }
        return null;
    }

    private HikariDataSource writer(String name, int maximum) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(owner.reads().jdbcUrl());
        config.setUsername(F3PostgresqlFixture.WRITER);
        config.setPassword(owner.writerPassword());
        config.setMaximumPoolSize(maximum);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(2_000);
        config.setValidationTimeout(1_000);
        config.setPoolName(name);
        config.addDataSourceProperty("connectTimeout", "3");
        config.addDataSourceProperty("socketTimeout", "12");
        config.setConnectionInitSql("SET statement_timeout=8000; SET lock_timeout=5000");
        return new HikariDataSource(config);
    }

    private void returned(HikariDataSource pool) {
        if (pool != null) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(TransactionSynchronizationManager.hasResource(pool)).isFalse();
            assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
        }
    }

    private void seedUser(long id) throws Exception {
        assertThat(scalar("SELECT count(*) FROM pawbridge_user.users WHERE user_id=" + id)).isEqualTo("0");
        try (Connection connection = admin(); var statement = connection.prepareStatement("INSERT INTO pawbridge_user.users(user_id,nickname,email,name,provider,role,password,care_reg_no,provider_id,created_at,updated_at) VALUES(?,?,?,'Auth synthetic','GOOGLE','ROLE_ADMIN','AUTH_PRIVATE','CARE_AUTH',?,TIMESTAMP '2025-01-01 18:01:02.123456',TIMESTAMP '2025-02-01 18:01:02.654321')")) {
            statement.setLong(1, id);
            statement.setString(2, "Auth" + id);
            statement.setString(3, "auth-" + id + "@fixture.invalid");
            statement.setString(4, "auth-provider-" + id);
            assertThat(statement.executeUpdate()).isOne();
            users.add(id);
        }
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
            try(Connection c=owner.admin();var s=c.createStatement();var r=s.executeQuery("SELECT value||':'||owner FROM pawbridge_f13_fixture.marker")){assertThat(r.next()).isTrue();assertThat(r.getString(1)).isEqualTo("pawbridge-f13-ci-only-v1:"+run);}
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException | SQLException failure) { throw new IllegalStateException("Owned auth-session evidence cannot be verified"); }
    }
    private static Path receipt(String property,String filename)throws java.io.IOException{Path p=Path.of(System.getProperty(property,"")).toAbsolutePath().normalize();assertThat(p.getFileName().toString()).isEqualTo(filename);assertThat(Files.isSymbolicLink(p)).isFalse();assertThat(Files.isRegularFile(p)).isTrue();assertThat(p.toRealPath()).isEqualTo(p);return p;}
    private static String sha(byte[] bytes)throws java.security.NoSuchAlgorithmException{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
}
