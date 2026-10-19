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
import com.pawbridge.backend.community.internal.comment.application.CreateComment;
import com.pawbridge.backend.community.internal.comment.application.UnsupportedCommentCharactersException;
import com.pawbridge.backend.community.internal.comment.infrastructure.JdbcCommentReader;
import com.pawbridge.backend.community.internal.comment.infrastructure.JdbcCommentWriter;
import com.pawbridge.backend.community.internal.comment.web.CommentCreateController;
import com.pawbridge.backend.community.internal.post.application.PostNotFoundException;
import com.pawbridge.backend.community.internal.post.infrastructure.JdbcPostReader;
import com.pawbridge.backend.user.api.UserNicknameQuery;
import com.pawbridge.backend.user.internal.nickname.infrastructure.JdbcUserNicknameQuery;
import com.pawbridge.communityservice.domain.entity.Comment;
import com.pawbridge.communityservice.domain.repository.CommentRepository;
import com.pawbridge.communityservice.dto.request.CreateCommentRequest;
import com.pawbridge.communityservice.dto.response.CommentResponse;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
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
import org.springframework.data.auditing.AuditingHandler;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.data.mapping.context.PersistentEntities;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real pinned JPA/auditing and validation contracts, on positively identified hosted fixture only. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class PostgresqlCommentCreateCompatibilityTest {
    private static final long POST = 9_910_000L;
    private static final long AUTHOR = 9_920_000L;
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-02T03:04:05.123456789Z"), ZoneId.systemDefault());
    private final LocalDateTime now = LocalDateTime.now(clock);
    private final ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
    private final Set<Long> commentIds = new LinkedHashSet<>();
    private F3PostgresqlFixture owner;
    private F2PostgresqlFixture fixture;
    private HikariDataSource reader;
    private HikariDataSource writer;
    private JdbcPostReader posts;
    private JdbcUserNicknameQuery nicknames;
    private JdbcCommentReader commentReader;
    private JdbcCommentWriter commentWriter;
    private SessionFactory legacy;
    private ValidatorFactory validators;

    @BeforeAll
    void openOwnedHostedFixtureAndExistingReaderWriterRoles() throws Exception {
        assertThat(System.getenv("GITHUB_ACTIONS")).isEqualTo("true");
        assertThat(System.getenv("RUNNER_ENVIRONMENT")).isEqualTo("github-hosted");
        assertThat(System.getenv("RUNNER_OS")).isEqualTo("Linux");
        assertThat(System.getenv("GITHUB_REPOSITORY")).isEqualTo("pawbridge/pawbridge-backend-k8s");
        assertThat(ZoneId.systemDefault().getId()).isIn("UTC", "Asia/Seoul");
        owner = F3PostgresqlFixture.open();
        fixture = owner.reads();
        try (Connection ignored = admin()) { }
        reader = pool(fixture.applicationUser(), fixture.applicationPassword(), true, "f9-comments-reader");
        writer = pool(F3PostgresqlFixture.WRITER, owner.writerPassword(), false, "f9-comments-writer");
        JdbcTemplate readJdbc = new JdbcTemplate(reader);
        readJdbc.setQueryTimeout(3);
        JdbcTransactionManager readManager = new JdbcTransactionManager(reader);
        readManager.setEnforceReadOnly(true);
        TransactionTemplate readTx = new TransactionTemplate(readManager);
        readTx.setReadOnly(true);
        readTx.setTimeout(3);
        JdbcTemplate writeJdbc = new JdbcTemplate(writer);
        writeJdbc.setQueryTimeout(5);
        TransactionTemplate writeTx = new TransactionTemplate(new JdbcTransactionManager(writer));
        writeTx.setTimeout(10);
        posts = new JdbcPostReader(readJdbc, readTx, mapper);
        nicknames = new JdbcUserNicknameQuery(readJdbc, readTx);
        commentReader = new JdbcCommentReader(readJdbc, readTx);
        commentWriter = new JdbcCommentWriter(writeJdbc, writeTx, clock);
        var oracleDataSource = new DriverManagerDataSource(fixture.jdbcUrl(),
                F3PostgresqlFixture.required("F2_TEST_ADMIN_USER"), F3PostgresqlFixture.required("F2_TEST_ADMIN_PASSWORD"));
        Configuration configuration = new Configuration().addAnnotatedClass(Comment.class)
                .setProperty(AvailableSettings.DEFAULT_SCHEMA, "pawbridge_community")
                .setProperty(AvailableSettings.HBM2DDL_AUTO, "none")
                .setProperty(AvailableSettings.JDBC_TIME_ZONE, "UTC")
                .setProperty(AvailableSettings.SHOW_SQL, "false");
        configuration.getProperties().put(AvailableSettings.JAKARTA_NON_JTA_DATASOURCE, oracleDataSource);
        legacy = configuration.buildSessionFactory();
        validators = Validation.buildDefaultValidatorFactory();
        returned();
    }

    @AfterEach
    void removeOnlyReturnedCommentIdentitiesAndThisTestsParentRows() throws Exception {
        if (owner != null) {
            try (Connection connection = admin(); var statement = connection.prepareStatement(
                    "DELETE FROM pawbridge_community.comments WHERE comment_id=?")) {
                for (long id : commentIds) { statement.setLong(1, id); statement.executeUpdate(); }
            }
        }
        commentIds.clear();
        if (fixture != null) fixture.close();
        if (reader != null && writer != null) returned();
    }

    @AfterAll
    void closeOracleValidationAndBothPools() {
        try {
            if (legacy != null) legacy.close();
            if (validators != null) validators.close();
        } finally {
            if (reader != null) reader.close();
            if (writer != null) writer.close();
        }
    }

    static Stream<Arguments> contentCases() {
        return Stream.of(Arguments.of(null, false), Arguments.of("", false), Arguments.of("  ", false),
                Arguments.of("\t\n", false), Arguments.of("\u2003", true), Arguments.of("\u3000", true),
                Arguments.of("  원문 공백  ", true), Arguments.of("길".repeat(100_000), true));
    }

    @ParameterizedTest
    @MethodSource("contentCases")
    void givenLegacyAndCandidateRequest__whenUsingActualValidator__thenBlankUnicodeAndRawLengthPoliciesAgree(
            String content, boolean accepted) {
        var validator = validators.getValidator();
        var oracle = validator.validate(new CreateCommentRequest(content));
        var candidate = validator.validate(new CommentCreateController.Request(content));
        assertThat(oracle.isEmpty()).isEqualTo(accepted);
        assertThat(candidate.isEmpty()).isEqualTo(accepted);
        assertThat(candidate.stream().map(v -> v.getPropertyPath() + ": " + v.getMessage()).toList())
                .isEqualTo(oracle.stream().map(v -> v.getPropertyPath() + ": " + v.getMessage()).toList());
        returned();
    }

    @ParameterizedTest
    @ValueSource(strings = {"MISSING", "PROTECTION", "REPORT", "ADOPTION", "COMMUNICATION"})
    void givenAnyBoardAndAnotherAuthorsPost__whenCreating__thenRealJpaAuditingDtoAndStoredDatesAgree(String board)
            throws Exception {
        seedParent(board, null);
        fixture.insertUser(AUTHOR, "실제 닉네임");
        long before = count();
        String stable = nonCommentState();
        String content = "  원문 한글\n내용  ";
        Comment expected = legacyInsert(content);
        CommentDetails actual = create(nicknames).create(POST, AUTHOR, content);
        commentIds.add(actual.commentId());
        assertThat(actual.commentId()).isPositive().isNotEqualTo(expected.getCommentId());
        assertThat(withoutIdentity(actual)).isEqualTo(withoutIdentity(CommentResponse.fromEntity(expected, "실제 닉네임")));
        assertThat(actual.createdAt()).isEqualTo(now);
        assertThat(actual.updatedAt()).isEqualTo(now);
        try (var session = legacy.openSession()) {
            Comment storedOracle = session.find(Comment.class, expected.getCommentId());
            Comment storedCandidate = session.find(Comment.class, actual.commentId());
            assertThat(withoutIdentity(CommentResponse.fromEntity(storedCandidate, "실제 닉네임")))
                    .isEqualTo(withoutIdentity(CommentResponse.fromEntity(storedOracle, "실제 닉네임")));
            // Both JDBC paths preserve PG TIMESTAMP(6); immediate Java nanos remain a separate contract.
            assertThat(storedCandidate.getCreatedAt()).isNotEqualTo(now);
        }
        var reloaded = commentReader.findByPostId(POST).stream()
                .filter(row -> row.commentId() == actual.commentId()).findFirst().orElseThrow();
        assertThat(reloaded.content()).isEqualTo(content);
        assertThat(count()).isEqualTo(before + 2);
        assertThat(nonCommentState()).isEqualTo(stable);
        returned();
    }

    @Test
    void givenAbsentThenDeletedParent__whenCreating__thenNoNicknameOrCommentWrite() throws Exception {
        long before = count();
        UserNicknameQuery forbidden = id -> { throw new AssertionError("Parent failure must precede nickname"); };
        assertThatThrownBy(() -> create(forbidden).create(POST, AUTHOR, "댓글")).isInstanceOf(PostNotFoundException.class);
        seedParent("COMMUNICATION", now);
        assertThatThrownBy(() -> create(forbidden).create(POST, AUTHOR, "댓글")).isInstanceOf(PostNotFoundException.class);
        assertThat(count()).isEqualTo(before);
        returned();
    }

    @Test
    void givenNicknameSelectFailureAndRecovery__whenCreating__thenTypedFallbackDoesNotPreventCommit() throws Exception {
        seedParent("COMMUNICATION", null);
        fixture.insertUser(AUTHOR, "회복한 닉네임");
        execute("REVOKE SELECT(nickname) ON pawbridge_user.users FROM pawbridge_f2_reader");
        try {
            CommentDetails first = create(nicknames).create(POST, AUTHOR, "대체 닉네임");
            commentIds.add(first.commentId());
            assertThat(first.authorNickname()).isEqualTo("사용자" + AUTHOR);
            returned();
        } finally { execute("GRANT SELECT(nickname) ON pawbridge_user.users TO pawbridge_f2_reader"); }
        CommentDetails second = create(nicknames).create(POST, AUTHOR, "회복");
        commentIds.add(second.commentId());
        assertThat(second.authorNickname()).isEqualTo("회복한 닉네임");
        returned();
    }

    @Test
    void givenUnexpectedNicknameFailure__whenCreatingThenRecovering__thenNoWriteBeforeFailure() throws Exception {
        seedParent("COMMUNICATION", null);
        long before = count();
        assertThatThrownBy(() -> create(id -> { throw new IllegalArgumentException("synthetic user failure"); })
                .create(POST, AUTHOR, "실패")).isInstanceOf(IllegalArgumentException.class);
        assertThat(count()).isEqualTo(before);
        CommentDetails recovered = create(nicknames).create(POST, AUTHOR, "회복");
        commentIds.add(recovered.commentId());
        assertThat(count()).isEqualTo(before + 1);
        returned();
    }

    @Test
    void givenParentDeletedAfterPrecheck__whenCreating__thenPreserveExistingRaceWithoutNewLockOrRecheck() throws Exception {
        seedParent("REPORT", null);
        UserNicknameQuery deleting = id -> {
            returned();
            try { execute("UPDATE pawbridge_community.posts SET deleted_at=CURRENT_TIMESTAMP WHERE post_id=" + POST); }
            catch (SQLException failure) { throw new IllegalStateException(failure); }
            return Optional.of("경쟁 닉네임");
        };
        CommentDetails actual = create(deleting).create(POST, AUTHOR, "경쟁 후 댓글");
        commentIds.add(actual.commentId());
        assertThat(posts.findById(POST)).isEmpty();
        assertThat(commentReader.findByPostId(POST)).extracting(row -> row.commentId()).contains(actual.commentId());
        returned();
    }

    @ParameterizedTest
    @ValueSource(strings = {"INSERT(content)", "SELECT(comment_id)"})
    void givenMissingMinimalWriterPrivilege__whenCreatingThenRestoring__thenNoPartialRowAndPoolRecovers(String privilege)
            throws Exception {
        seedParent("COMMUNICATION", null);
        long before = count();
        String target = "pawbridge_community.comments";
        execute("REVOKE " + privilege + " ON " + target + " FROM pawbridge_f3_writer");
        try {
            assertThatThrownBy(() -> create(nicknames).create(POST, AUTHOR, "권한 실패"))
                    .isInstanceOf(DataAccessException.class);
            assertThat(count()).isEqualTo(before);
            returned();
        } finally { execute("GRANT " + privilege + " ON " + target + " TO pawbridge_f3_writer"); }
        CommentDetails actual = create(nicknames).create(POST, AUTHOR, "권한 회복");
        commentIds.add(actual.commentId());
        assertThat(count()).isEqualTo(before + 1);
        returned();
    }

    @Test
    void givenIdentityColumnWithoutSequencePrivileges__whenInserting__thenDatabaseStillGeneratesTheId() throws Exception {
        seedParent("COMMUNICATION", null);
        try (Connection connection = admin(); var statement = connection.createStatement();
                var row = statement.executeQuery("SELECT has_sequence_privilege('pawbridge_f3_writer',"
                        + "'pawbridge_community.comments_comment_id_seq','USAGE'),"
                        + "has_sequence_privilege('pawbridge_f3_writer',"
                        + "'pawbridge_community.comments_comment_id_seq','SELECT'),"
                        + "has_sequence_privilege('pawbridge_f3_writer',"
                        + "'pawbridge_community.comments_comment_id_seq','UPDATE')")) {
            assertThat(row.next()).isTrue();
            assertThat(row.getBoolean(1)).isFalse();
            assertThat(row.getBoolean(2)).isFalse();
            assertThat(row.getBoolean(3)).isFalse();
        }
        long before = count();
        CommentDetails actual = create(nicknames).create(POST, AUTHOR, "identity 권한 경계");
        commentIds.add(actual.commentId());
        assertThat(actual.commentId()).isPositive();
        assertThat(count()).isEqualTo(before + 1);
        returned();
    }

    @Test
    void givenNamedCharsetCheck__whenCreatingUnsupportedThenSupportedContent__thenKnownRollbackAndRecovery() throws Exception {
        seedParent("COMMUNICATION", null);
        long before = count();
        assertThatThrownBy(() -> create(nicknames).create(POST, AUTHOR, "문자 🤖"))
                .isInstanceOf(UnsupportedCommentCharactersException.class);
        assertThat(count()).isEqualTo(before);
        CommentDetails actual = create(nicknames).create(POST, AUTHOR, "지원 문자");
        commentIds.add(actual.commentId());
        assertThat(count()).isEqualTo(before + 1);
        returned();
    }

    @Test
    void givenDeferredDatabaseRejectionAtCommit__whenCreating__thenIdentityReturnDoesNotBecomeSuccess() throws Exception {
        seedParent("COMMUNICATION", null);
        long before = count();
        execute("CREATE FUNCTION pawbridge_f9_fixture.reject_commit() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN RAISE EXCEPTION USING ERRCODE='23514', SCHEMA='pawbridge_community', TABLE='comments', "
                + "CONSTRAINT='ck_rollback_charset_comments', MESSAGE='synthetic commit rejection'; END; $$; "
                + "CREATE CONSTRAINT TRIGGER f9_reject_commit AFTER INSERT ON pawbridge_community.comments "
                + "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION pawbridge_f9_fixture.reject_commit()");
        try {
            assertThatThrownBy(() -> create(nicknames).create(POST, AUTHOR, "커밋 시 거부"))
                    .isInstanceOf(UnsupportedCommentCharactersException.class);
            assertThat(count()).isEqualTo(before);
            returned();
        } finally {
            execute("DROP TRIGGER f9_reject_commit ON pawbridge_community.comments; "
                    + "DROP FUNCTION pawbridge_f9_fixture.reject_commit()");
        }
        CommentDetails actual = create(nicknames).create(POST, AUTHOR, "커밋 회복");
        commentIds.add(actual.commentId());
        assertThat(count()).isEqualTo(before + 1);
        returned();
    }

    private CreateComment create(UserNicknameQuery users) {
        return new CreateComment(posts, id -> { returned(); return users.findNickname(id); },
                (postId, authorId, content) -> { returned(); return commentWriter.create(postId, authorId, content); });
    }

    private Comment legacyInsert(String content) throws Exception {
        try (Connection ignored = admin()) { }
        Comment comment = Comment.builder().postId(POST).authorId(AUTHOR).content(content).build();
        // Use the real Spring Data annotation handler, as in the existing F4 auditing oracle.
        AuditingHandler auditing = new AuditingHandler(new PersistentEntities(List.of()));
        auditing.setDateTimeProvider(() -> Optional.of(now));
        auditing.markCreated(comment);
        try (var session = legacy.openSession()) {
            var transaction = session.beginTransaction();
            CommentRepository repository = new JpaRepositoryFactory(session).getRepository(CommentRepository.class);
            repository.saveAndFlush(comment);
            transaction.commit();
        }
        commentIds.add(comment.getCommentId());
        return comment;
    }

    private void seedParent(String board, LocalDateTime deletedAt) throws SQLException {
        fixture.insertPost(POST, AUTHOR + 99, board, "[]", now, now, deletedAt);
    }

    private HikariDataSource pool(String user, String password, boolean readOnly, String name) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(fixture.jdbcUrl());
        config.setUsername(user); config.setPassword(password); config.setReadOnly(readOnly);
        config.setMaximumPoolSize(1); config.setMinimumIdle(0); config.setConnectionTimeout(2_000);
        config.setValidationTimeout(1_000); config.setPoolName(name);
        return new HikariDataSource(config);
    }

    private Connection admin() throws SQLException {
        Connection connection = owner.admin();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT value,owner FROM pawbridge_f9_fixture.marker")) {
            String expected = System.getenv("GITHUB_RUN_ID") + "-" + System.getenv("GITHUB_RUN_ATTEMPT");
            assertThat(System.getenv("F9_CI_OWNER")).isEqualTo(expected);
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo("pawbridge-f9-ci-only-v1");
            assertThat(rows.getString(2)).isEqualTo(expected);
            assertThat(rows.next()).isFalse();
        } catch (RuntimeException | Error | SQLException failure) { connection.close(); throw failure; }
        return connection;
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = admin(); var statement = connection.createStatement()) { statement.execute(sql); }
    }

    private long count() throws SQLException {
        try (Connection connection = admin(); var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT count(*) FROM pawbridge_community.comments")) {
            rows.next(); return rows.getLong(1);
        }
    }

    private String nonCommentState() throws SQLException {
        try (Connection connection = admin(); var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT json_build_object("
                        + "'posts',(SELECT COALESCE(json_agg(p ORDER BY post_id),'[]') FROM pawbridge_community.posts p),"
                        + "'outbox',(SELECT COALESCE(json_agg(e ORDER BY outbox_id),'[]') FROM pawbridge_community.outbox_events e),"
                        + "'search',(SELECT COALESCE(json_agg(s ORDER BY post_id),'[]') FROM pawbridge_community.post_search_documents s))")) {
            rows.next(); return rows.getString(1);
        }
    }

    private JsonNode withoutIdentity(Object value) {
        ObjectNode node = mapper.valueToTree(value);
        node.remove("commentId");
        return node;
    }

    private void returned() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TransactionSynchronizationManager.hasResource(reader)).isFalse();
        assertThat(TransactionSynchronizationManager.hasResource(writer)).isFalse();
        assertThat(reader.getMaximumPoolSize()).isOne();
        assertThat(writer.getMaximumPoolSize()).isOne();
        assertThat(reader.getHikariPoolMXBean().getActiveConnections()).isZero();
        assertThat(writer.getHikariPoolMXBean().getActiveConnections()).isZero();
    }
}
