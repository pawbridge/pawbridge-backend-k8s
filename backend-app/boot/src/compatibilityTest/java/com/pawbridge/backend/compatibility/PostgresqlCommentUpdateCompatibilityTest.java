package com.pawbridge.backend.compatibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pawbridge.backend.community.internal.comment.application.CommentDetails;
import com.pawbridge.backend.community.internal.comment.application.CommentNotFoundException;
import com.pawbridge.backend.community.internal.comment.application.CommentSnapshot;
import com.pawbridge.backend.community.internal.comment.application.UnsupportedCommentCharactersException;
import com.pawbridge.backend.community.internal.comment.application.UpdateComment;
import com.pawbridge.backend.community.internal.comment.domain.CommentNotOwnedException;
import com.pawbridge.backend.community.internal.comment.infrastructure.JdbcCommentReader;
import com.pawbridge.backend.community.internal.comment.infrastructure.JdbcCommentWriter;
import com.pawbridge.backend.community.internal.comment.web.CommentUpdateController;
import com.pawbridge.backend.user.api.UserNicknameQuery;
import com.pawbridge.backend.user.internal.nickname.infrastructure.JdbcUserNicknameQuery;
import com.pawbridge.communityservice.dto.request.UpdateCommentRequest;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Calendar;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;
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

/** Real auditing/no-dirty behavior and controlled races, only after the owned F11 ACL extension. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class PostgresqlCommentUpdateCompatibilityTest {
    private static final long BASE = 9_960_000L;
    private static final long AUTHOR = BASE + 42;
    private static final long PARENT = BASE + 51;
    private static final String ORIGINAL_CONTENT = "최초 원문 댓글";
    private static final String NICKNAME = "수정 작성자";
    private static final LocalDateTime ORIGINAL = LocalDateTime.parse("2025-01-02T03:04:05.123456");
    private final Clock updateClock = Clock.fixed(Instant.parse("2026-01-02T03:04:05.123456789Z"), ZoneId.systemDefault());
    private final ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
    private final Set<Long> ownedComments = new LinkedHashSet<>();
    private boolean ownsAuthor;
    private F3PostgresqlFixture owner;
    private F2PostgresqlFixture fixture;
    private HikariDataSource reader;
    private HikariDataSource writer;
    private JdbcCommentReader comments;
    private JdbcCommentWriter modifications;
    private JdbcUserNicknameQuery nicknames;
    private ValidatorFactory validators;
    private F11LegacyCommentOracle oracle;

    @BeforeAll
    void openOnlyTheOwnedHostedF11FixtureAndRealAuditingContext() throws Exception {
        assertThat(System.getenv("GITHUB_ACTIONS")).isEqualTo("true");
        assertThat(System.getenv("RUNNER_ENVIRONMENT")).isEqualTo("github-hosted");
        assertThat(System.getenv("RUNNER_OS")).isEqualTo("Linux");
        assertThat(System.getenv("GITHUB_REPOSITORY")).isEqualTo("pawbridge/pawbridge-backend-k8s");
        assertThat(ZoneId.systemDefault().getId()).isIn("UTC", "Asia/Seoul");
        owner = F3PostgresqlFixture.open(); fixture = owner.reads(); verifyOwnership();
        reader = pool(fixture.applicationUser(), fixture.applicationPassword(), true, "f11-comment-reader");
        writer = pool(F3PostgresqlFixture.WRITER, owner.writerPassword(), false, "f11-comment-writer");
        JdbcTemplate readJdbc = new JdbcTemplate(reader); readJdbc.setQueryTimeout(3);
        JdbcTransactionManager readManager = new JdbcTransactionManager(reader); readManager.setEnforceReadOnly(true);
        TransactionTemplate readTx = new TransactionTemplate(readManager); readTx.setReadOnly(true); readTx.setTimeout(3);
        comments = new JdbcCommentReader(readJdbc, readTx); nicknames = new JdbcUserNicknameQuery(readJdbc, readTx);
        JdbcTemplate writeJdbc = new JdbcTemplate(writer); writeJdbc.setQueryTimeout(5);
        TransactionTemplate writeTx = new TransactionTemplate(new JdbcTransactionManager(writer)); writeTx.setTimeout(10);
        modifications = new JdbcCommentWriter(writeJdbc, writeTx, updateClock);
        validators = Validation.buildDefaultValidatorFactory();
        oracle = new F11LegacyCommentOracle(owner, updateClock, this::verifyOwnership);
        returned();
    }

    @AfterEach
    void removeOnlySuccessfullySeededIdentitiesAndCheckBothPools() throws Exception {
        if (owner != null) {
            try (Connection connection = admin(); var statement = connection.prepareStatement(
                    "DELETE FROM pawbridge_community.comments WHERE comment_id=?")) {
                for (long id : ownedComments) { statement.setLong(1, id); statement.executeUpdate(); }
            }
            if (ownsAuthor) execute("DELETE FROM pawbridge_user.users WHERE user_id=" + AUTHOR);
        }
        ownedComments.clear(); ownsAuthor = false;
        if (reader != null && writer != null) returned();
    }

    @AfterAll
    void closeAuditingOracleValidatorAndExistingPools() {
        try { if (oracle != null) oracle.close(); }
        finally {
            try { if (validators != null) validators.close(); }
            finally {
                if (reader != null) reader.close();
                if (writer != null) writer.close();
            }
        }
    }

    static Stream<Arguments> contentCases() {
        return Stream.of(Arguments.of(null, false), Arguments.of("", false), Arguments.of("  ", false),
                Arguments.of("\t\n", false), Arguments.of("\u2003", true), Arguments.of("\u3000", true),
                Arguments.of("  원문 공백  ", true), Arguments.of("길".repeat(100_000), true));
    }

    @ParameterizedTest
    @MethodSource("contentCases")
    void givenPinnedLegacyAndCandidateRequest__whenUsingActualValidator__thenRequiredUnicodeAndLengthContractsMatch(
            String content, boolean accepted) {
        var validator = validators.getValidator();
        var original = validator.validate(new UpdateCommentRequest(content));
        var candidate = validator.validate(new CommentUpdateController.Request(content));
        assertThat(original.isEmpty()).isEqualTo(accepted); assertThat(candidate.isEmpty()).isEqualTo(accepted);
        assertThat(candidate.stream().map(v -> v.getPropertyPath() + ": " + v.getMessage()).toList())
                .isEqualTo(original.stream().map(v -> v.getPropertyPath() + ": " + v.getMessage()).toList());
        returned();
    }

    @ParameterizedTest
    @ValueSource(strings = {"same", "changed", "unicode-space", "raw-long"})
    void givenRealAuditingAndSameOrChangedContent__whenUpdating__thenOldResponseTimeAndCommittedDirtyStateMatchJpa(String mode)
            throws Exception {
        seed(BASE + 1, null); seed(BASE + 2, null);
        CommentSnapshot before = comments.findById(BASE + 2).orElseThrow();
        String neighbors = unrelatedState();
        String content = switch (mode) {
            case "same" -> ORIGINAL_CONTENT;
            case "changed" -> "수정한 원문";
            case "unicode-space" -> "\u2003";
            default -> "  원문\n" + "가".repeat(20_000) + "  ";
        };
        AtomicInteger oracleLookups = new AtomicInteger();
        var expected = oracle.update(BASE + 1, content, () -> { oracleLookups.incrementAndGet(); return NICKNAME; });
        AtomicInteger candidateLookups = new AtomicInteger();
        CommentDetails actual = update(id -> { candidateLookups.incrementAndGet(); return Optional.of(NICKNAME); })
                .update(BASE + 2, AUTHOR, content);
        assertThat(withoutResponseIdentity(actual)).isEqualTo(withoutResponseIdentity(expected));
        assertThat(actual.content()).isEqualTo(content);
        assertThat(actual.createdAt()).isEqualTo(before.createdAt());
        assertThat(actual.updatedAt()).isEqualTo(before.updatedAt());
        assertThat(oracleLookups).hasValue(1); assertThat(candidateLookups).hasValue(1);
        assertThat(withoutDatabaseIdentity(row(BASE + 2))).isEqualTo(withoutDatabaseIdentity(row(BASE + 1)));
        CommentSnapshot committed = comments.findById(BASE + 2).orElseThrow();
        if (mode.equals("same")) assertThat(committed).isEqualTo(before);
        else assertThat(committed.updatedAt()).isNotEqualTo(before.updatedAt());
        assertThat(unrelatedState()).isEqualTo(neighbors);
        returned();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void givenAbsentOrDeletedComment__whenUpdatingByAnotherAuthor__thenNotFoundPrecedesUserAndWrite(boolean exists)
            throws Exception {
        if (exists) seed(BASE + 3, ORIGINAL);
        JsonNode before = row(BASE + 3);
        assertThatThrownBy(() -> update(id -> { throw new AssertionError("User must not run"); })
                .update(BASE + 3, AUTHOR + 1, "새 본문")).isInstanceOf(CommentNotFoundException.class);
        assertThat(row(BASE + 3)).isEqualTo(before); returned();
    }

    @Test
    void givenAnotherAuthorsActiveComment__whenUpdating__thenForbiddenPrecedesUserAndPreservesTheEntireRow() throws Exception {
        seed(BASE + 4, null); JsonNode before = row(BASE + 4);
        assertThatThrownBy(() -> update(id -> { throw new AssertionError("User must not run"); })
                .update(BASE + 4, AUTHOR + 1, "새 본문")).isInstanceOf(CommentNotOwnedException.class);
        assertThat(row(BASE + 4)).isEqualTo(before); returned();
    }

    @ParameterizedTest
    @ValueSource(strings = {"UPDATE(content)", "UPDATE(updated_at)", "SELECT(comment_id)"})
    void givenMissingWriterPrivilege__whenKeepingThenChangingContent__thenNoOpStillLooksUpNicknameAndChangedWriteRollsBack(String privilege)
            throws Exception {
        seed(BASE + 5, null); JsonNode before = row(BASE + 5);
        AtomicInteger lookups = new AtomicInteger();
        UserNicknameQuery user = id -> { lookups.incrementAndGet(); return Optional.of(NICKNAME); };
        execute("REVOKE " + privilege + " ON pawbridge_community.comments FROM pawbridge_f3_writer");
        try {
            CommentDetails same = update(user).update(BASE + 5, AUTHOR, ORIGINAL_CONTENT);
            assertThat(same.authorNickname()).isEqualTo(NICKNAME); assertThat(row(BASE + 5)).isEqualTo(before);
            assertThatThrownBy(() -> update(user).update(BASE + 5, AUTHOR, "권한 없는 변경"))
                    .isInstanceOf(DataAccessException.class);
            assertThat(row(BASE + 5)).isEqualTo(before); assertThat(lookups).hasValue(2); returned();
        } finally { execute("GRANT " + privilege + " ON pawbridge_community.comments TO pawbridge_f3_writer"); }
        update(user).update(BASE + 5, AUTHOR, "권한 회복");
        assertThat(comments.findById(BASE + 5).orElseThrow().content()).isEqualTo("권한 회복"); returned();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void givenNicknameSelectFailure__whenKeepingOrChangingContent__thenTypedFallbackAndNextLookupRecover(boolean changed)
            throws Exception {
        seed(BASE + 6, null); seedUser();
        execute("REVOKE SELECT(nickname) ON pawbridge_user.users FROM pawbridge_f2_reader");
        try {
            var result = update(nicknames).update(BASE + 6, AUTHOR, changed ? "다른 내용" : ORIGINAL_CONTENT);
            assertThat(result.authorNickname()).isEqualTo("사용자" + AUTHOR); returned();
        } finally { execute("GRANT SELECT(nickname) ON pawbridge_user.users TO pawbridge_f2_reader"); }
        var result = update(nicknames).update(BASE + 6, AUTHOR, "회복 후 내용");
        assertThat(result.authorNickname()).isEqualTo(NICKNAME); returned();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void givenUnexpectedUserFailure__whenKeepingOrChangingContent__thenNoModificationAndNextRequestRecovers(boolean changed)
            throws Exception {
        seed(BASE + 7, null); JsonNode before = row(BASE + 7);
        assertThatThrownBy(() -> update(id -> { throw new IllegalArgumentException("synthetic user failure"); })
                .update(BASE + 7, AUTHOR, changed ? "다른 내용" : ORIGINAL_CONTENT)).isInstanceOf(IllegalArgumentException.class);
        assertThat(row(BASE + 7)).isEqualTo(before);
        update(id -> Optional.of(NICKNAME)).update(BASE + 7, AUTHOR, "회복한 내용");
        assertThat(comments.findById(BASE + 7).orElseThrow().content()).isEqualTo("회복한 내용"); returned();
    }

    @Test
    void givenUnsupportedChangedCharacters__whenUpdatingThenRecovering__thenKnownConstraintRollsBack() throws Exception {
        seed(BASE + 8, null); JsonNode before = row(BASE + 8);
        assertThatThrownBy(() -> update(id -> Optional.of(NICKNAME)).update(BASE + 8, AUTHOR, "문자 🤖"))
                .isInstanceOf(UnsupportedCommentCharactersException.class);
        assertThat(row(BASE + 8)).isEqualTo(before);
        update(id -> Optional.of(NICKNAME)).update(BASE + 8, AUTHOR, "지원하는 내용");
        assertThat(comments.findById(BASE + 8).orElseThrow().content()).isEqualTo("지원하는 내용"); returned();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void givenDeferredCommitRejection__whenChangingThenRecovering__thenOnlyNamedCharsetIsTypedAndTheOriginalRowSurvives(boolean charset)
            throws Exception {
        seed(BASE + 9, null); JsonNode before = row(BASE + 9);
        String constraint = charset ? "ck_rollback_charset_comments" : "f11_synthetic_commit_rejection";
        execute("CREATE FUNCTION pawbridge_f11_fixture.reject_update_commit() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN IF NEW.comment_id=" + (BASE + 9) + " THEN RAISE EXCEPTION USING ERRCODE='23514', "
                + "SCHEMA='pawbridge_community', TABLE='comments', CONSTRAINT='" + constraint
                + "', MESSAGE='synthetic update commit rejection'; END IF; RETURN NEW; END; $$; "
                + "CREATE CONSTRAINT TRIGGER f11_reject_update_commit AFTER UPDATE ON pawbridge_community.comments "
                + "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION pawbridge_f11_fixture.reject_update_commit()");
        try {
            var failure = assertThatThrownBy(() -> update(id -> Optional.of(NICKNAME)).update(BASE + 9, AUTHOR, "커밋 거부 내용"));
            if (charset) failure.isInstanceOf(UnsupportedCommentCharactersException.class);
            else failure.isInstanceOf(RuntimeException.class).isNotInstanceOf(UnsupportedCommentCharactersException.class);
            assertThat(row(BASE + 9)).isEqualTo(before); returned();
        } finally {
            execute("DROP TRIGGER f11_reject_update_commit ON pawbridge_community.comments; "
                    + "DROP FUNCTION pawbridge_f11_fixture.reject_update_commit()");
        }
        update(id -> Optional.of(NICKNAME)).update(BASE + 9, AUTHOR, "커밋 회복 내용");
        assertThat(comments.findById(BASE + 9).orElseThrow().content()).isEqualTo("커밋 회복 내용"); returned();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void givenConcurrentContentAfterSnapshot__whenRequestingOriginalOrConcurrentValue__thenInitialDirtyDecisionMatchesJpa(boolean changed)
            throws Exception {
        seed(BASE + 10, null); seed(BASE + 11, null);
        String content = changed ? "동시 본문 B" : ORIGINAL_CONTENT;
        var expected = oracle.update(BASE + 10, content, () -> {
            externalUpdate(BASE + 10, "동시 본문 B", ORIGINAL.plusDays(1)); return NICKNAME;
        });
        var actual = update(id -> {
            externalUpdate(BASE + 11, "동시 본문 B", ORIGINAL.plusDays(1)); return Optional.of(NICKNAME);
        }).update(BASE + 11, AUTHOR, content);
        assertThat(withoutResponseIdentity(actual)).isEqualTo(withoutResponseIdentity(expected));
        assertThat(withoutDatabaseIdentity(row(BASE + 11))).isEqualTo(withoutDatabaseIdentity(row(BASE + 10)));
        assertThat(actual.updatedAt()).isEqualTo(ORIGINAL);
        assertThat(comments.findById(BASE + 11).orElseThrow().content()).isEqualTo("동시 본문 B");
        if (changed) assertThat(comments.findById(BASE + 11).orElseThrow().updatedAt()).isNotEqualTo(ORIGINAL.plusDays(1));
        else assertThat(comments.findById(BASE + 11).orElseThrow().updatedAt()).isEqualTo(ORIGINAL.plusDays(1));
        returned();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void givenDeletionDuringNicknameLookup__whenKeepingOrChangingContent__thenCandidatePreservesDeletionAndShowsLegacyDifference(boolean changed)
            throws Exception {
        seed(BASE + 12, null); seed(BASE + 13, null);
        String content = changed ? "삭제와 경합한 수정" : ORIGINAL_CONTENT;
        var expected = oracle.update(BASE + 12, content, () -> { externalDelete(BASE + 12); return NICKNAME; });
        var actual = update(id -> { externalDelete(BASE + 13); return Optional.of(NICKNAME); })
                .update(BASE + 13, AUTHOR, content);
        assertThat(withoutResponseIdentity(actual)).isEqualTo(withoutResponseIdentity(expected));
        assertThat(row(BASE + 13).get("deleted_at").isNull()).isFalse();
        assertThat(comments.findById(BASE + 13)).isEmpty();
        assertThat(row(BASE + 13).get("content").asText()).isEqualTo(content);
        // A dirty legacy full-row update can resurrect; the narrow candidate deliberately does not.
        assertThat(row(BASE + 12).get("deleted_at").isNull()).isEqualTo(changed);
        returned();
    }

    @Test
    void givenPhysicalRemovalAfterSnapshot__whenChanging__thenUpdateZeroIsUnexpectedAndNotRetried() throws Exception {
        seed(BASE + 14, null);
        assertThatThrownBy(() -> update(id -> {
            try { execute("DELETE FROM pawbridge_community.comments WHERE comment_id=" + (BASE + 14)); }
            catch (SQLException failure) { throw new IllegalStateException(failure); }
            return Optional.of(NICKNAME);
        }).update(BASE + 14, AUTHOR, "삭제 이후 수정")).isInstanceOf(IllegalStateException.class);
        assertThat(row(BASE + 14).isNull()).isTrue(); returned();
    }

    private UpdateComment update(UserNicknameQuery user) {
        return new UpdateComment(comments, id -> {
            returned(); Optional<String> nickname = user.findNickname(id); returned(); return nickname;
        }, (id, content) -> { returned(); modifications.update(id, content); });
    }

    private void seed(long id, LocalDateTime deleted) throws Exception {
        assertThat(ownedComments).doesNotContain(id);
        try (Connection connection = admin(); var statement = connection.prepareStatement(
                "INSERT INTO pawbridge_community.comments(comment_id,post_id,author_id,content,created_at,updated_at,deleted_at) "
                        + "VALUES(?,?,?,?,?,?,?)")) {
            statement.setLong(1, id); statement.setLong(2, PARENT); statement.setLong(3, AUTHOR);
            statement.setString(4, ORIGINAL_CONTENT);
            statement.setTimestamp(5, Timestamp.valueOf(ORIGINAL), utc());
            statement.setTimestamp(6, Timestamp.valueOf(ORIGINAL), utc());
            if (deleted == null) statement.setNull(7, java.sql.Types.TIMESTAMP);
            else statement.setTimestamp(7, Timestamp.valueOf(deleted), utc());
            statement.executeUpdate(); ownedComments.add(id);
        }
    }

    private void seedUser() throws SQLException {
        assertThat(ownsAuthor).isFalse();
        try (Connection connection = admin(); var statement = connection.prepareStatement(
                "INSERT INTO pawbridge_user.users(user_id,nickname) VALUES(?,?)")) {
            statement.setLong(1, AUTHOR); statement.setString(2, NICKNAME); statement.executeUpdate(); ownsAuthor = true;
        }
    }

    private void externalUpdate(long id, String content, LocalDateTime updated) {
        try (Connection connection = admin(); var statement = connection.prepareStatement(
                "UPDATE pawbridge_community.comments SET content=?,updated_at=? WHERE comment_id=?")) {
            statement.setString(1, content); statement.setTimestamp(2, Timestamp.valueOf(updated), utc()); statement.setLong(3, id);
            assertThat(statement.executeUpdate()).isOne();
        } catch (SQLException failure) { throw new IllegalStateException(failure); }
    }

    private void externalDelete(long id) {
        try (Connection connection = admin(); var statement = connection.prepareStatement(
                "UPDATE pawbridge_community.comments SET deleted_at=?,updated_at=? WHERE comment_id=?")) {
            statement.setTimestamp(1, Timestamp.valueOf(ORIGINAL.plusDays(1)), utc());
            statement.setTimestamp(2, Timestamp.valueOf(ORIGINAL.plusDays(1)), utc()); statement.setLong(3, id);
            assertThat(statement.executeUpdate()).isOne();
        } catch (SQLException failure) { throw new IllegalStateException(failure); }
    }

    private JsonNode row(long id) throws Exception {
        return mapper.readTree(scalar("SELECT COALESCE((SELECT row_to_json(c)::text FROM pawbridge_community.comments c "
                + "WHERE comment_id=" + id + "),'null')"));
    }

    private JsonNode withoutResponseIdentity(Object value) {
        ObjectNode result = mapper.valueToTree(value); result.remove("commentId"); return result;
    }

    private JsonNode withoutDatabaseIdentity(JsonNode value) {
        ObjectNode result = value.deepCopy(); result.remove("comment_id"); return result;
    }

    private String unrelatedState() throws SQLException {
        return scalar("SELECT json_build_object("
                + "'users',(SELECT COALESCE(json_agg(u ORDER BY user_id),'[]') FROM pawbridge_user.users u),"
                + "'posts',(SELECT COALESCE(json_agg(p ORDER BY post_id),'[]') FROM pawbridge_community.posts p),"
                + "'outbox',(SELECT COALESCE(json_agg(e ORDER BY outbox_id),'[]') FROM pawbridge_community.outbox_events e),"
                + "'search',(SELECT COALESCE(json_agg(s ORDER BY post_id),'[]') FROM pawbridge_community.post_search_documents s))");
    }

    private Connection admin() throws SQLException {
        Connection connection = owner.admin();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT 9,value,owner FROM pawbridge_f9_fixture.marker UNION ALL "
                        + "SELECT 10,value,owner FROM pawbridge_f10_fixture.marker UNION ALL "
                        + "SELECT 11,value,owner FROM pawbridge_f11_fixture.marker ORDER BY 1")) {
            String expected = System.getenv("GITHUB_RUN_ID") + "-" + System.getenv("GITHUB_RUN_ATTEMPT");
            for (int stage : new int[]{9, 10, 11}) {
                assertThat(System.getenv("F" + stage + "_CI_OWNER")).isEqualTo(expected);
                assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(stage);
                assertThat(rows.getString(2)).isEqualTo("pawbridge-f" + stage + "-ci-only-v1");
                assertThat(rows.getString(3)).isEqualTo(expected);
            }
            assertThat(rows.next()).isFalse();
        } catch (SQLException | RuntimeException | Error failure) { connection.close(); throw failure; }
        return connection;
    }

    private void verifyOwnership() {
        try (Connection ignored = admin()) { }
        catch (SQLException failure) { throw new IllegalStateException(failure); }
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = admin(); var statement = connection.createStatement()) { statement.execute(sql); }
    }

    private String scalar(String sql) throws SQLException {
        try (Connection connection = admin(); var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).isTrue(); return rows.getString(1);
        }
    }

    private static Calendar utc() { return Calendar.getInstance(TimeZone.getTimeZone("UTC")); }

    private HikariDataSource pool(String user, String password, boolean readOnly, String name) {
        HikariConfig config = new HikariConfig(); config.setJdbcUrl(fixture.jdbcUrl());
        config.setUsername(user); config.setPassword(password); config.setReadOnly(readOnly);
        config.setMaximumPoolSize(1); config.setMinimumIdle(0); config.setConnectionTimeout(2_000);
        config.setValidationTimeout(1_000); config.setPoolName(name); return new HikariDataSource(config);
    }

    private void returned() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TransactionSynchronizationManager.hasResource(reader)).isFalse();
        assertThat(TransactionSynchronizationManager.hasResource(writer)).isFalse();
        assertThat(reader.getMaximumPoolSize()).isOne(); assertThat(writer.getMaximumPoolSize()).isOne();
        assertThat(reader.getHikariPoolMXBean().getActiveConnections()).isZero();
        assertThat(writer.getHikariPoolMXBean().getActiveConnections()).isZero();
    }
}
