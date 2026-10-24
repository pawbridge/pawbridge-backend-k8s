package com.pawbridge.backend.compatibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.user.internal.adminupdate.application.AdminUserUpdate;
import com.pawbridge.backend.user.internal.adminupdate.infrastructure.JdbcAdminUserUpdate;
import com.pawbridge.backend.user.internal.adminupdate.web.AdminUserUpdateExceptionHandler;
import com.pawbridge.userservice.dto.request.AdminUserUpdateRequest;
import com.pawbridge.userservice.entity.Role;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual F13 JPA/auditing/commit reused; only the new administrator service and its exact late ACL differ. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresqlAdminUserUpdateCompatibilityTest {
    private static final long BASE = 9_985_600;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-01T18:04:05.123456Z"), ZoneOffset.UTC);
    private static final String ORIGINAL_RECEIPT_SHA = "96e0d308622eedc6d41415fc6618af2114ade873cad7c50ca505690dcf4c4891";
private static final String MEMBER_PROOF="source_sha=f06a72a06bd26662c647bde780dfc7a27bf7349c\nfixture=F29 plus User deletion and installed-chat cleanup\nnew_business_ddl=0\nsynthetic_tables_added=6\nsynthetic_identity_sequences_added=2\nnew_schema=0\nnew_marker=0\nsnapshot_tables=24\nreader_delta=0\nnew_sequence_acl=0\ncf93ed908b18f63f59832cd0fe5bcf500ccf9e4c39972deeda5de446c4dd953d  member-ddl-fragment-0\neb3fe612fe9d4aff8372539b0d59e1da4c34266036ce88f1ed0ba51b88f5e139  member-ddl-fragment-1\nb705222e7a9c9a6964628c937cff63470e1dd30103b27111ae3c5d94d284e1ec  member-ddl-fragment-2\n7afb8a3d992eba1258d61a610a418133f56766e9def3eb2e105c584bedcd2fdb  member-ddl-fragment-3\n5604c03964f051343785c2d30f9bf20022d6a0c867152b6175168c4f5ea81754  member-exact-grants\n";
    private final Set<Long> inserted = new LinkedHashSet<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private F3PostgresqlFixture owner;
    private HikariDataSource writer;
    private String baseline;

    @BeforeAll void openOnlyTheOwnedLateFixtureAndOneWriter() throws Exception {
        assertThat(System.getProperty("user.timezone")).isEqualTo("UTC");
        owner = F3PostgresqlFixture.open(); verifyOwnership();
        assertThat(scalar("SELECT count(*) FROM pawbridge_user.users WHERE user_id BETWEEN " + BASE + " AND " + (BASE + 99))).isEqualTo("0");
        writer = writer("admin-user-update"); baseline = snapshot();
    }
    @AfterEach void restoreOnlyAcknowledgedRowsAndReturnTheWriter() throws Exception {
        cleanup(); returned();
        if (baseline != null) assertThat(snapshot().equals(baseline)).as("All twenty-four tables restore exactly").isTrue();
    }
    @AfterAll void closeOnlyTheOwnedWriter() { if (writer != null) writer.close(); }

    @ParameterizedTest(name = "givenAnActualDirtyManagedUser__whenUpdatingAsAdmin__thenMatchTheOriginalMicrosecondAuditAndPreserveEveryOtherColumn[{index}]") @ValueSource(strings = {"nickname-and-role", "role-only", "shelter-demotion"})
    void givenAnActualDirtyManagedUser__whenUpdatingAsAdmin__thenMatchTheOriginalMicrosecondAuditAndPreserveEveryOtherColumn(String scenario) throws Exception {
        Role stored = scenario.equals("shelter-demotion") ? Role.ROLE_SHELTER : Role.ROLE_USER;
        String nickname = scenario.equals("nickname-and-role") ? "AuChanged" : null;
        Role role = scenario.equals("shelter-demotion") ? Role.ROLE_USER : Role.ROLE_ADMIN;
        String expected = null;
        for (boolean source : new boolean[] {true, false}) {
            seed(BASE + 1, "AuOriginal", stored);
            String immutable = immutable(BASE + 1);
            try (var round = round(source, writer, () -> { }, () -> { })) {
                round.update(BASE + 1, nickname, role, "CARE1");
                assertThat(immutable(BASE + 1)).isEqualTo(immutable);
                assertThat(scalar("SELECT updated_at::text FROM pawbridge_user.users WHERE user_id=" + (BASE + 1))).isEqualTo("2026-03-01 18:04:05.123456");
                assertThat(scalar("SELECT role FROM pawbridge_user.users WHERE user_id=" + (BASE + 1))).isEqualTo(role.name());
                if (expected == null) expected = row(BASE + 1);
                else assertThat(row(BASE + 1).equals(expected)).as("Identical stored row after real JpaTM/JdbcTM commits").isTrue();
            } finally { cleanup(); }
        }
    }

    @ParameterizedTest(name = "givenNoDirtyProperty__whenTheOriginalStillSaves__thenNeitherImplementationUpdatesTheRowOrAuditTimestamp[{index}]") @ValueSource(strings = {"nulls", "same-values"})
    void givenNoDirtyProperty__whenTheOriginalStillSaves__thenNeitherImplementationUpdatesTheRowOrAuditTimestamp(String scenario) throws Exception {
        for (boolean source : new boolean[] {true, false}) {
            seed(BASE + 1, "AuOriginal", Role.ROLE_SHELTER); String before = snapshot();
            try (var round = round(source, writer, () -> { }, () -> { })) {
                round.update(BASE + 1, scenario.equals("nulls") ? null : "AuOriginal",
                        scenario.equals("nulls") ? null : Role.ROLE_SHELTER, scenario.equals("nulls") ? null : "CARE1");
                assertThat(snapshot().equals(before)).isTrue();
                if (source) { assertThat(round.source.saves.get()).isOne(); assertThat(round.source.duplicateQueries.get()).isZero(); }
                else assertThat(round.jdbc.updates).isZero();
            } finally { cleanup(); }
        }
    }

    @ParameterizedTest(name = "givenCompetingBusinessRejections__whenUpdating__thenPreserveMissingApprovalAndNicknamePrecedenceWithoutAnyMutation[{index}]") @ValueSource(strings = {"missing", "approval-role", "approval-care", "duplicate-other", "duplicate-self-folded"})
    void givenCompetingBusinessRejections__whenUpdating__thenPreserveMissingApprovalAndNicknamePrecedenceWithoutAnyMutation(String scenario) throws Exception {
        for (boolean source : new boolean[] {true, false}) {
            if (!scenario.equals("missing")) seed(BASE + 1, "AuOriginal", Role.ROLE_USER);
            seed(BASE + 2, "AuTaken", Role.ROLE_USER); String before = snapshot();
            try (var round = round(source, writer, () -> { }, () -> { })) {
                String nickname = scenario.equals("duplicate-self-folded") ? "auoriginal" : "AuTaken";
                Throwable failure = catchThrowable(() -> round.update(BASE + 1, nickname,
                        scenario.equals("approval-role") ? Role.ROLE_SHELTER : null,
                        scenario.equals("approval-care") ? "DIFFERENT" : null));
                assertThat(failure).isNotNull();
                if (scenario.equals("missing")) assertThat(failure.getClass().getSimpleName()).isEqualTo("UserNotFoundException");
                else if (scenario.startsWith("approval")) assertThat(failure.getClass().getSimpleName()).isEqualTo(source ? "ShelterApplicationException" : "ShelterApprovalRequiredException");
                else assertThat(failure.getClass().getSimpleName()).isEqualTo("NicknameDuplicateException");
                assertThat(snapshot().equals(before)).isTrue();
                if (source && (scenario.equals("missing") || scenario.startsWith("approval"))) assertThat(round.source.duplicateQueries.get()).isZero();
            } finally { cleanup(); }
        }
    }

    @ParameterizedTest(name = "givenAnExactNewColumnPrivilegeRevoked__whenUpdatingThenRestoring__thenRollbackTheWholeRowAndRecover[{index}]") @ValueSource(strings = {"care-read", "role-write"})
    void givenAnExactNewColumnPrivilegeRevoked__whenUpdatingThenRestoring__thenRollbackTheWholeRowAndRecover(String denied) throws Exception {
        seed(BASE + 1, "AuOriginal", Role.ROLE_USER); String before = snapshot();
        String clause = denied.equals("care-read") ? "SELECT(care_reg_no)" : "UPDATE(role)";
        try (var round = round(false, writer, () -> { }, () -> { })) {
            execute("REVOKE " + clause + " ON pawbridge_user.users FROM " + F3PostgresqlFixture.WRITER);
            try {
                if (denied.equals("role-write")) { round.update(BASE + 1, null, null, null); assertThat(round.jdbc.updates).isZero(); }
                Throwable failure = catchThrowable(() -> round.update(BASE + 1, "AuChanged", Role.ROLE_ADMIN, null));
                assertThat(failure).isInstanceOf(RuntimeException.class); assertThat(sqlState(failure)).isEqualTo("42501");
                assertThat(snapshot().equals(before)).isTrue(); assertServerFailure((RuntimeException) failure, false);
            } finally { verifyOwnership(); execute("GRANT " + clause + " ON pawbridge_user.users TO " + F3PostgresqlFixture.WRITER); }
            round.update(BASE + 1, "AuChanged", Role.ROLE_ADMIN, null);
        }
        assertThat(scalar("SELECT role FROM pawbridge_user.users WHERE user_id=" + (BASE + 1))).isEqualTo("ROLE_ADMIN");
    }

    @ParameterizedTest(name = "givenTheLookupHasLockedOneMember__whenAnIndependentConnectionAttemptsToChangeIt__thenObserveTheActualPgBlockUntilCommit[{index}]") @ValueSource(booleans = {true, false})
    void givenTheLookupHasLockedOneMember__whenAnIndependentConnectionAttemptsToChangeIt__thenObserveTheActualPgBlockUntilCommit(boolean source) throws Exception {
        seed(BASE + 1, "AuOriginal", Role.ROLE_USER);
        var looked = new CountDownLatch(1); var release = new CountDownLatch(1);
        Runnable afterRead = () -> { looked.countDown(); await(release); };
        var worker = Executors.newSingleThreadExecutor();
        try (var round = round(source, writer, afterRead, () -> { }); Connection outside = admin()) {
            try (var statement = outside.createStatement()) { statement.execute("SET lock_timeout='700ms'; SET statement_timeout='2s'"); }
            var future = worker.submit(() -> round.update(BASE + 1, null, Role.ROLE_ADMIN, null));
            try {
                assertThat(looked.await(8, TimeUnit.SECONDS)).isTrue();
                Throwable blocked = catchThrowable(() -> { try (var statement = outside.createStatement()) {
                    statement.executeUpdate("UPDATE pawbridge_user.users SET password='AU_EXTERNAL' WHERE user_id=" + (BASE + 1));
                }});
                assertThat(sqlState(blocked)).isEqualTo("55P03");
            } finally { release.countDown(); worker.shutdown(); if (!worker.awaitTermination(15, TimeUnit.SECONDS)) throw new IllegalStateException("Owned lock worker did not return"); }
            future.get(12, TimeUnit.SECONDS);
            assertThat(scalar("SELECT password FROM pawbridge_user.users WHERE user_id=" + (BASE + 1))).isEqualTo("AU_PRIVATE");
        } finally { release.countDown(); worker.shutdownNow(); assertThat(worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }

    @ParameterizedTest(name = "givenTwoDistinctLockedMembersPassTheAvailableCheck__whenTheirUniqueNicknameUpdatesRace__thenOneCommitAndOneSql23505RemainServerFailuresWithoutPartialRoleChanges[{index}]") @ValueSource(booleans = {true, false})
    void givenTwoDistinctLockedMembersPassTheAvailableCheck__whenTheirUniqueNicknameUpdatesRace__thenOneCommitAndOneSql23505RemainServerFailuresWithoutPartialRoleChanges(boolean source) throws Exception {
        seed(BASE + 1, "AuFirst", Role.ROLE_USER); seed(BASE + 2, "AuSecond", Role.ROLE_USER);
        String firstBefore = row(BASE + 1), secondBefore = row(BASE + 2);
        var barrier = new CyclicBarrier(2);
        Runnable meet = () -> { try { barrier.await(8, TimeUnit.SECONDS); } catch (Exception failure) { throw new IllegalStateException("Owned nickname synchronization did not complete"); } };
        var workers = Executors.newFixedThreadPool(2);
        try (var otherWriter = writer("admin-user-update-race");
                var first = round(source, writer, () -> { }, meet);
                var second = round(source, otherWriter, () -> { }, meet)) {
            try {
            var a = workers.submit(() -> attempt(first, BASE + 1)); var b = workers.submit(() -> attempt(second, BASE + 2));
            var outcomes = new ArrayList<RuntimeException>(); outcomes.add(a.get(20, TimeUnit.SECONDS)); outcomes.add(b.get(20, TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter(e -> e == null).count()).isOne();
            RuntimeException failure = outcomes.stream().filter(e -> e != null).findFirst().orElseThrow();
            assertThat(sqlState(failure)).isEqualTo("23505"); assertThat(failure.getClass().getSimpleName()).isNotEqualTo("NicknameDuplicateException");
            assertServerFailure(failure, source);
            long loser = outcomes.getFirst() != null ? BASE + 1 : BASE + 2;
            assertThat(row(loser).equals(loser == BASE + 1 ? firstBefore : secondBefore)).as("The unique loser rolls back nickname, role and audit together").isTrue();
            assertThat(scalar("SELECT count(*) FROM pawbridge_user.users WHERE nickname='AuRace' AND role='ROLE_ADMIN' AND user_id IN(" + (BASE + 1) + "," + (BASE + 2) + ")")).isEqualTo("1");
            } finally { workers.shutdownNow(); assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
        } finally { workers.shutdownNow(); }
    }

    private RuntimeException attempt(Round round, long id) { try { round.update(id, "AuRace", Role.ROLE_ADMIN, null); return null; } catch (RuntimeException failure) { return failure; } }
    private Round round(boolean source, HikariDataSource ds, Runnable read, Runnable available) { return new Round(source, ds, read, available); }
    private final class Round implements AutoCloseable {
        final F13LegacyNicknameOracle source; final ObservingJdbc jdbc; final AdminUserUpdate candidate;
        Round(boolean original, HikariDataSource ds, Runnable read, Runnable available) {
            if (original) { source = new F13LegacyNicknameOracle(owner, CLOCK, PostgresqlAdminUserUpdateCompatibilityTest.this::verifyOwnership, read, available); jdbc = null; candidate = null; }
            else { source = null; jdbc = new ObservingJdbc(ds, read, available); var tx = new TransactionTemplate(new JdbcTransactionManager(ds)); tx.setTimeout(15);
                candidate = new JdbcAdminUserUpdate(jdbc, tx, () -> LocalDateTime.now(CLOCK)); }
        }
        void update(long id, String nickname, Role role, String care) {
            if (source != null) source.updateAsAdmin(id, new AdminUserUpdateRequest(nickname, role, care));
            else candidate.update(id, new AdminUserUpdate.Changes(nickname, role == null ? null : AdminUserUpdate.Role.valueOf(role.name()), care));
        }
        @Override public void close() { if (source != null) source.close(); }
    }
    private static final class ObservingJdbc extends JdbcTemplate {
        final Runnable read, available; int updates;
        ObservingJdbc(HikariDataSource ds, Runnable read, Runnable available) { super(ds); this.read = read; this.available = available; setQueryTimeout(5); }
        @Override public <T> T query(String sql, ResultSetExtractor<T> extractor, Object... args) throws DataAccessException {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
            T result = super.query(sql, extractor, args); if (sql.contains("FOR UPDATE")) read.run(); return result;
        }
        @Override public <T> T queryForObject(String sql, Class<T> type, Object... args) throws DataAccessException {
            T result = super.queryForObject(sql, type, args); if (Boolean.FALSE.equals(result)) available.run(); return result;
        }
        @Override public int update(PreparedStatementCreator creator) throws DataAccessException { updates++; return super.update(creator); }
    }
    private void assertServerFailure(RuntimeException failure, boolean source) throws Exception {
        org.springframework.http.ResponseEntity<?> response;
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(com.pawbridge.userservice.exception.common.GlobalExceptionRestAdvice.class);
        var level = logger.getLevel(); logger.setLevel(ch.qos.logback.classic.Level.OFF);
        try {
            if (source) { var advice = new com.pawbridge.userservice.exception.common.GlobalExceptionRestAdvice(); response = failure instanceof DataAccessException e ? advice.dbException(e) : advice.serverException(failure); }
            else response = new AdminUserUpdateExceptionHandler().unexpected(failure);
        } finally { logger.setLevel(level); }
        var wire = mapper.readTree(mapper.writeValueAsBytes(response.getBody()));
        assertThat(response.getStatusCode().value()).isEqualTo(500); assertThat(wire.size()).isEqualTo(3);
        assertThat(wire.get("code").asInt()).isEqualTo(500); assertThat(wire.get("message").asText()).isEqualTo("서버 에러!"); assertThat(wire.get("data").isNull()).isTrue();
    }
    private HikariDataSource writer(String name) {
        HikariConfig config = new HikariConfig(); config.setJdbcUrl(owner.reads().jdbcUrl()); config.setUsername(F3PostgresqlFixture.WRITER); config.setPassword(owner.writerPassword());
        config.setMaximumPoolSize(1); config.setMinimumIdle(0); config.setConnectionTimeout(2_000); config.setValidationTimeout(1_000); config.setPoolName(name);
        config.addDataSourceProperty("connectTimeout", "3"); config.addDataSourceProperty("socketTimeout", "12"); config.setConnectionInitSql("SET statement_timeout=8000; SET lock_timeout=5000"); return new HikariDataSource(config);
    }
    private void returned() { if (writer != null) { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); assertThat(TransactionSynchronizationManager.hasResource(writer)).isFalse(); assertThat(writer.getHikariPoolMXBean().getActiveConnections()).isZero(); } }
    private void seed(long id, String nickname, Role role) throws Exception {
        assertThat(scalar("SELECT count(*) FROM pawbridge_user.users WHERE user_id=" + id)).isEqualTo("0");
        try (Connection c = admin(); var s = c.prepareStatement("INSERT INTO pawbridge_user.users(user_id,nickname,email,name,provider,role,password,care_reg_no,provider_id,created_at,updated_at) VALUES(?,?,?,'AU synthetic','LOCAL',?,'AU_PRIVATE','CARE1','AU_PRIVATE',TIMESTAMP '2025-01-01 18:01:02.123456',TIMESTAMP '2025-02-01 18:01:02.123456')")) {
            s.setLong(1,id);s.setString(2,nickname);s.setString(3,"admin-update-"+id+"@fixture.invalid");s.setString(4,role.name());assertThat(s.executeUpdate()).isOne();inserted.add(id);
        }
    }
    private void cleanup() throws Exception {
        if (owner == null) return;
        for (long id : inserted) {
            assertThat(scalar("SELECT (SELECT count(*) FROM pawbridge_user.contact_deletions WHERE user_id="+id+")+(SELECT count(*) FROM pawbridge_user.favorites WHERE user_id="+id+")+(SELECT count(*) FROM pawbridge_user.shelter_applications WHERE user_id="+id+")+(SELECT count(*) FROM pawbridge_community.private_note_members WHERE member_id="+id+")")).isEqualTo("0");
            execute("DELETE FROM pawbridge_user.users WHERE user_id=" + id);
        }
        inserted.clear();
    }
    private String row(long id) throws Exception { return scalar("SELECT to_jsonb(u)::text FROM pawbridge_user.users u WHERE user_id=" + id); }
    private String immutable(long id) throws Exception { return scalar("SELECT (to_jsonb(u)-'nickname'-'role'-'care_reg_no'-'updated_at')::text FROM pawbridge_user.users u WHERE user_id=" + id); }
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
    private void execute(String sql) throws Exception { try(Connection c=admin();var s=c.createStatement()){s.execute(sql);} }
    private String scalar(String sql) throws Exception { try(Connection c=admin();var s=c.createStatement();var rows=s.executeQuery(sql)){assertThat(rows.next()).isTrue();return rows.getString(1);} }
    private Connection admin() throws SQLException { verifyOwnership(); return owner.admin(); }
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
            try(Connection c=owner.admin();var s=c.createStatement();var r=s.executeQuery("SELECT value||':'||owner FROM pawbridge_f13_fixture.marker")){assertThat(r.next()).isTrue();assertThat(r.getString(1)).isEqualTo("pawbridge-f13-ci-only-v1:"+run);}
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException | SQLException failure) { throw new IllegalStateException("Owned administrator update evidence cannot be verified"); }
    }
    private static Path receipt(String property,String filename)throws java.io.IOException{Path p=Path.of(System.getProperty(property,"")).toAbsolutePath().normalize();assertThat(p.getFileName().toString()).isEqualTo(filename);assertThat(Files.isSymbolicLink(p)).isFalse();assertThat(Files.isRegularFile(p)).isTrue();assertThat(p.toRealPath()).isEqualTo(p);return p;}
    private static String sha(byte[] bytes)throws java.security.NoSuchAlgorithmException{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static void await(CountDownLatch latch){try{if(!latch.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Owned row lock release did not complete");}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException("Owned row lock interrupted");}}
    private static String sqlState(Throwable failure){var seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable,Boolean>());for(Throwable next=failure;next!=null&&seen.add(next);next=next.getCause())if(next instanceof SQLException sql&&sql.getSQLState()!=null)return sql.getSQLState();return null;}
}
