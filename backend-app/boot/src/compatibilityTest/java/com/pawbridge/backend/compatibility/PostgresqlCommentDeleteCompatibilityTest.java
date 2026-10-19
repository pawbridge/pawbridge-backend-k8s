package com.pawbridge.backend.compatibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pawbridge.backend.community.internal.comment.application.CommentNotFoundException;
import com.pawbridge.backend.community.internal.comment.application.UnsupportedCommentCharactersException;
import com.pawbridge.backend.community.internal.comment.domain.CommentNotOwnedException;
import com.pawbridge.backend.community.internal.comment.infrastructure.JdbcCommentDeletion;
import com.pawbridge.backend.community.internal.comment.infrastructure.JdbcCommentReader;
import com.pawbridge.communityservice.domain.entity.Comment;
import com.pawbridge.communityservice.domain.repository.CommentRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Calendar;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.auditing.AuditingHandler;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.data.mapping.context.PersistentEntities;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Run only after F3-F9 contracts finish, on the explicitly owned F10 ACL extension. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class PostgresqlCommentDeleteCompatibilityTest {
    private static final long BASE = 9_940_000L;
    private static final long AUTHOR = BASE + 42;
    private static final long PARENT = BASE + 51;
    private static final String CONTENT = "보존할 원문 댓글";
    private static final LocalDateTime ORIGINAL = LocalDateTime.parse("2025-01-02T03:04:05.123456");
    private final ObjectMapper mapper = new ObjectMapper();
    private final Set<Long> ownedComments = new LinkedHashSet<>();
    private F3PostgresqlFixture owner;
    private F2PostgresqlFixture fixture;
    private HikariDataSource reader;
    private HikariDataSource writer;
    private JdbcTemplate writeJdbc;
    private TransactionTemplate writeTx;
    private JdbcCommentReader comments;
    private SessionFactory legacy;

    @BeforeAll
    void openOnlyTheOwnedHostedF10FixtureAndExistingRoles() throws Exception {
        assertThat(System.getenv("GITHUB_ACTIONS")).isEqualTo("true");
        assertThat(System.getenv("RUNNER_ENVIRONMENT")).isEqualTo("github-hosted");
        assertThat(System.getenv("RUNNER_OS")).isEqualTo("Linux");
        assertThat(System.getenv("GITHUB_REPOSITORY")).isEqualTo("pawbridge/pawbridge-backend-k8s");
        assertThat(ZoneId.systemDefault().getId()).isIn("UTC", "Asia/Seoul");
        owner = F3PostgresqlFixture.open();
        fixture = owner.reads();
        try (Connection ignored = admin()) { }
        reader = pool(fixture.applicationUser(), fixture.applicationPassword(), true, "f10-comment-reader");
        writer = pool(F3PostgresqlFixture.WRITER, owner.writerPassword(), false, "f10-comment-writer");
        JdbcTemplate readJdbc = new JdbcTemplate(reader);
        readJdbc.setQueryTimeout(3);
        JdbcTransactionManager readManager = new JdbcTransactionManager(reader);
        readManager.setEnforceReadOnly(true);
        TransactionTemplate readTx = new TransactionTemplate(readManager);
        readTx.setReadOnly(true); readTx.setTimeout(3);
        comments = new JdbcCommentReader(readJdbc, readTx);
        writeJdbc = new JdbcTemplate(writer); writeJdbc.setQueryTimeout(5);
        writeTx = transaction(writer);
        Configuration configuration = new Configuration().addAnnotatedClass(Comment.class)
                .setProperty(AvailableSettings.DEFAULT_SCHEMA, "pawbridge_community")
                .setProperty(AvailableSettings.HBM2DDL_AUTO, "none")
                .setProperty(AvailableSettings.JDBC_TIME_ZONE, "UTC")
                .setProperty(AvailableSettings.SHOW_SQL, "false");
        configuration.getProperties().put(AvailableSettings.JAKARTA_NON_JTA_DATASOURCE,
                new DriverManagerDataSource(fixture.jdbcUrl(), F3PostgresqlFixture.required("F2_TEST_ADMIN_USER"),
                        F3PostgresqlFixture.required("F2_TEST_ADMIN_PASSWORD")));
        legacy = configuration.buildSessionFactory();
        returned();
    }

    @AfterEach
    void removeOnlyThisClassSeededRowsAndCheckReturnedConnections() throws Exception {
        if (owner != null) {
            try (Connection connection = admin(); var statement = connection.prepareStatement(
                    "DELETE FROM pawbridge_community.comments WHERE comment_id=?")) {
                for (long id : ownedComments) { statement.setLong(1, id); statement.executeUpdate(); }
            }
        }
        ownedComments.clear();
        if (reader != null && writer != null) returned();
    }

    @AfterAll
    void closeOracleAndBothExistingSizePools() {
        try { if (legacy != null) legacy.close(); }
        finally {
            if (reader != null) reader.close();
            if (writer != null) writer.close();
        }
    }

    @Test
    void givenRealDeleteAndDistinctAuditingTime__whenDeleting__thenTwoStoredTimesAndPreservedColumnsMatchJpa()
            throws Exception {
        seed(BASE + 1, AUTHOR, null); seed(BASE + 2, AUTHOR, null);
        JsonNode originalCandidate = row(BASE + 2);
        String unrelated = unrelatedState();
        LocalDateTime deleted;
        LocalDateTime modified;
        try (var session = legacy.openSession()) {
            var tx = session.beginTransaction();
            CommentRepository repository = new JpaRepositoryFactory(session).getRepository(CommentRepository.class);
            Comment entity = repository.findByCommentIdAndDeletedAtIsNull(BASE + 1).orElseThrow();
            entity.delete();
            deleted = entity.getDeletedAt();
            modified = deleted.plusSeconds(2).plusNanos(2_000);
            audit(entity, modified);
            repository.saveAndFlush(entity);
            tx.commit();
        }
        new JdbcCommentDeletion(writeJdbc, writeTx, new TwoReadClock(deleted, modified)).delete(BASE + 2, AUTHOR);
        assertThat(withoutIdentity(row(BASE + 2))).isEqualTo(withoutIdentity(row(BASE + 1)));
        assertThat(row(BASE + 2).get("deleted_at")).isNotEqualTo(row(BASE + 2).get("updated_at"));
        // UTC JDBC binding can place the raw TIMESTAMP on the previous date in Seoul.
        // Deletion must preserve the actual stored creation value, independently of that representation.
        assertThat(row(BASE + 2).get("created_at")).isEqualTo(originalCandidate.get("created_at"));
        assertThat(row(BASE + 2).get("content").asText()).isEqualTo(CONTENT);
        assertThat(comments.findByPostId(PARENT)).isEmpty();
        JsonNode after = row(BASE + 2);
        assertThatThrownBy(() -> deletion().delete(BASE + 2, AUTHOR)).isInstanceOf(CommentNotFoundException.class);
        assertThat(row(BASE + 2)).isEqualTo(after);
        assertThat(unrelatedState()).isEqualTo(unrelated);
        returned();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void givenAbsentOrAlreadyDeletedComment__whenAnotherAuthorDeletes__thenNotFoundPrecedesOwnershipAndWrites(boolean exists)
            throws Exception {
        if (exists) seed(BASE + 3, AUTHOR, ORIGINAL);
        JsonNode before = row(BASE + 3);
        assertThatThrownBy(() -> deletion().delete(BASE + 3, AUTHOR + 1)).isInstanceOf(CommentNotFoundException.class);
        assertThat(row(BASE + 3)).isEqualTo(before);
        returned();
    }

    @Test
    void givenActiveCommentOwnedBySomeoneElse__whenDeleting__thenForbiddenAndEntireRowUnchanged() throws Exception {
        seed(BASE + 4, AUTHOR, null);
        JsonNode before = row(BASE + 4);
        assertThatThrownBy(() -> deletion().delete(BASE + 4, AUTHOR + 1)).isInstanceOf(CommentNotOwnedException.class);
        assertThat(row(BASE + 4)).isEqualTo(before);
        returned();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SELECT(comment_id)", "SELECT(author_id)", "SELECT(deleted_at)",
            "UPDATE(deleted_at)", "UPDATE(updated_at)"})
    void givenMissingNecessaryCommentPrivilege__whenDeletingThenRestoring__thenRollbackAndNextRequestRecovers(String privilege)
            throws Exception {
        seed(BASE + 5, AUTHOR, null);
        JsonNode before = row(BASE + 5);
        execute("REVOKE " + privilege + " ON pawbridge_community.comments FROM pawbridge_f3_writer");
        try {
            assertThatThrownBy(() -> deletion().delete(BASE + 5, AUTHOR)).isInstanceOf(DataAccessException.class);
            assertThat(row(BASE + 5)).isEqualTo(before);
            returned();
        } finally { execute("GRANT " + privilege + " ON pawbridge_community.comments TO pawbridge_f3_writer"); }
        deletion().delete(BASE + 5, AUTHOR);
        assertThat(row(BASE + 5).get("deleted_at").isNull()).isFalse();
        returned();
    }

    @Test
    void givenNoParentOrUserRowsAndNoWriterReadGrantsForThem__whenDeleting__thenOnlyCommentPrivilegesAreNeeded()
            throws Exception {
        seed(BASE + 6, AUTHOR, null);
        // F3 granted SELECT at table level. Column REVOKE would leave that right in place.
        assertThat(scalar("SELECT has_table_privilege('pawbridge_f3_writer','pawbridge_community.posts','SELECT')"))
                .isEqualTo("t");
        String originalAcl = scalar("SELECT relacl::text FROM pg_class WHERE oid='pawbridge_community.posts'::regclass");
        assertThat(scalar("SELECT count(*) FROM pawbridge_community.posts WHERE post_id=" + PARENT)).isEqualTo("0");
        assertThat(scalar("SELECT count(*) FROM pawbridge_user.users WHERE user_id=" + AUTHOR)).isEqualTo("0");
        execute("REVOKE SELECT ON pawbridge_community.posts FROM pawbridge_f3_writer");
        try {
            assertThat(scalar("SELECT has_any_column_privilege('pawbridge_f3_writer','pawbridge_community.posts','SELECT') "
                    + "OR has_any_column_privilege('pawbridge_f3_writer','pawbridge_user.users','SELECT')")).isEqualTo("f");
            deletion().delete(BASE + 6, AUTHOR);
            assertThat(row(BASE + 6).get("deleted_at").isNull()).isFalse();
            returned();
        } finally { execute("GRANT SELECT ON pawbridge_community.posts TO pawbridge_f3_writer"); }
        assertThat(scalar("SELECT relacl::text FROM pg_class WHERE oid='pawbridge_community.posts'::regclass"))
                .isEqualTo(originalAcl);
    }

    @Test
    void givenTwoIndependentTransactionsThatAlreadyReadActive__whenDeleting__thenBothCommitAsInUnversionedJpa()
            throws Exception {
        seed(BASE + 7, AUTHOR, null); seed(BASE + 8, AUTHOR, null);
        try (var first = legacy.openSession(); var second = legacy.openSession()) {
            var tx1 = first.beginTransaction(); var tx2 = second.beginTransaction();
            Comment one = first.find(Comment.class, BASE + 7);
            Comment two = second.find(Comment.class, BASE + 7);
            assertThat(one.getDeletedAt()).isNull(); assertThat(two.getDeletedAt()).isNull();
            one.delete(); audit(one, LocalDateTime.now()); tx1.commit();
            two.delete(); audit(two, LocalDateTime.now()); tx2.commit();
        }
        CountDownLatch bothRead = new CountDownLatch(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var one = executor.submit(() -> independentDeletionAfterRead(bothRead).delete(BASE + 8, AUTHOR));
            var two = executor.submit(() -> independentDeletionAfterRead(bothRead).delete(BASE + 8, AUTHOR));
            one.get(15, TimeUnit.SECONDS); two.get(15, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(row(BASE + 7).get("deleted_at").isNull()).isFalse();
        assertThat(row(BASE + 8).get("deleted_at").isNull()).isFalse();
        assertThatThrownBy(() -> deletion().delete(BASE + 8, AUTHOR)).isInstanceOf(CommentNotFoundException.class);
        returned();
    }

    @Test
    void givenPhysicalRemovalAfterActiveRead__whenDeleting__thenLegacyStaleFailureAndCandidateUnexpectedFailure()
            throws Exception {
        seed(BASE + 9, AUTHOR, null); seed(BASE + 10, AUTHOR, null);
        try (var session = legacy.openSession()) {
            var tx = session.beginTransaction();
            Comment entity = session.find(Comment.class, BASE + 9);
            execute("DELETE FROM pawbridge_community.comments WHERE comment_id=" + (BASE + 9));
            entity.delete(); audit(entity, LocalDateTime.now());
            try {
                assertThatThrownBy(tx::commit).isInstanceOf(RuntimeException.class)
                        .satisfies(failure -> assertThat(hasStaleCause(failure)).isTrue());
            } finally { if (tx.isActive()) tx.rollback(); }
        }
        JdbcTemplate interposed = afterRead(() -> execute("DELETE FROM pawbridge_community.comments WHERE comment_id=" + (BASE + 10)));
        assertThatThrownBy(() -> new JdbcCommentDeletion(interposed, writeTx).delete(BASE + 10, AUTHOR))
                .isInstanceOf(IllegalStateException.class);
        assertThat(row(BASE + 9).isNull()).isTrue(); assertThat(row(BASE + 10).isNull()).isTrue();
        returned();
    }

    @Test
    void givenConcurrentContentChangeAfterRead__whenDeleting__thenNarrowCandidatePreservesItWhileLegacyFullUpdateOverwrites()
            throws Exception {
        seed(BASE + 11, AUTHOR, null); seed(BASE + 12, AUTHOR, null);
        try (var session = legacy.openSession()) {
            var tx = session.beginTransaction();
            Comment entity = session.find(Comment.class, BASE + 11);
            execute("UPDATE pawbridge_community.comments SET content='경쟁 중 새 본문' WHERE comment_id=" + (BASE + 11));
            entity.delete(); audit(entity, LocalDateTime.now());
            tx.commit();
        }
        JdbcTemplate interposed = afterRead(() -> execute("UPDATE pawbridge_community.comments SET content='경쟁 중 새 본문' "
                + "WHERE comment_id=" + (BASE + 12)));
        new JdbcCommentDeletion(interposed, writeTx).delete(BASE + 12, AUTHOR);
        assertThat(row(BASE + 11).get("content").asText()).isEqualTo(CONTENT);
        assertThat(row(BASE + 12).get("content").asText()).isEqualTo("경쟁 중 새 본문");
        assertThat(row(BASE + 11).get("deleted_at").isNull()).isFalse();
        assertThat(row(BASE + 12).get("deleted_at").isNull()).isFalse();
        // This deliberate two-column UPDATE difference does not fix a later legacy PUT resurrecting a row.
        returned();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void givenDeferredCommitRejection__whenDeletingThenRecovering__thenOnlyNamedCharsetIsTypedAndNoPartialChange(boolean charset)
            throws Exception {
        seed(BASE + 13, AUTHOR, null);
        JsonNode before = row(BASE + 13);
        String constraint = charset ? "ck_rollback_charset_comments" : "f10_synthetic_commit_rejection";
        execute("CREATE FUNCTION pawbridge_f10_fixture.reject_delete_commit() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN IF NEW.comment_id=" + (BASE + 13) + " THEN RAISE EXCEPTION USING ERRCODE='23514', "
                + "SCHEMA='pawbridge_community', TABLE='comments', CONSTRAINT='" + constraint
                + "', MESSAGE='synthetic delete commit rejection'; END IF; RETURN NEW; END; $$; "
                + "CREATE CONSTRAINT TRIGGER f10_reject_delete_commit AFTER UPDATE ON pawbridge_community.comments "
                + "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION pawbridge_f10_fixture.reject_delete_commit()");
        try {
            var failure = assertThatThrownBy(() -> deletion().delete(BASE + 13, AUTHOR));
            if (charset) failure.isInstanceOf(UnsupportedCommentCharactersException.class);
            else failure.isInstanceOf(RuntimeException.class).isNotInstanceOf(UnsupportedCommentCharactersException.class);
            assertThat(row(BASE + 13)).isEqualTo(before);
            returned();
        } finally {
            execute("DROP TRIGGER f10_reject_delete_commit ON pawbridge_community.comments; "
                    + "DROP FUNCTION pawbridge_f10_fixture.reject_delete_commit()");
        }
        deletion().delete(BASE + 13, AUTHOR);
        assertThat(row(BASE + 13).get("deleted_at").isNull()).isFalse();
        returned();
    }

    private JdbcCommentDeletion independentDeletionAfterRead(CountDownLatch bothRead) {
        var source = new DriverManagerDataSource(fixture.jdbcUrl(), F3PostgresqlFixture.WRITER, owner.writerPassword());
        JdbcTemplate jdbc = new JdbcTemplate(source) {
            @Override
            public <T> T query(PreparedStatementCreator creator, ResultSetExtractor<T> extractor) throws DataAccessException {
                T result = super.query(creator, extractor);
                bothRead.countDown();
                try {
                    if (!bothRead.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("synthetic read barrier timeout");
                } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
                return result;
            }
        };
        jdbc.setQueryTimeout(5);
        return new JdbcCommentDeletion(jdbc, transaction(source));
    }

    private JdbcTemplate afterRead(SqlAction action) {
        return new JdbcTemplate(writer) {
            @Override
            public <T> T query(PreparedStatementCreator creator, ResultSetExtractor<T> extractor) throws DataAccessException {
                T result = super.query(creator, extractor);
                try { action.run(); } catch (SQLException failure) { throw new IllegalStateException(failure); }
                return result;
            }
        };
    }

    private JdbcCommentDeletion deletion() { return new JdbcCommentDeletion(writeJdbc, writeTx); }

    private void seed(long id, long authorId, LocalDateTime deleted) throws Exception {
        assertThat(ownedComments).doesNotContain(id);
        try (Connection connection = admin(); var statement = connection.prepareStatement(
                "INSERT INTO pawbridge_community.comments(comment_id,post_id,author_id,content,created_at,updated_at,deleted_at) "
                        + "VALUES(?,?,?,?,?,?,?)")) {
            statement.setLong(1, id); statement.setLong(2, PARENT); statement.setLong(3, authorId); statement.setString(4, CONTENT);
            for (int index : new int[]{5, 6}) statement.setTimestamp(index, Timestamp.valueOf(ORIGINAL), utc());
            if (deleted == null) statement.setNull(7, java.sql.Types.TIMESTAMP);
            else statement.setTimestamp(7, Timestamp.valueOf(deleted), utc());
            statement.executeUpdate();
            ownedComments.add(id);
        }
    }

    private JsonNode row(long id) throws Exception {
        String json = scalar("SELECT COALESCE((SELECT row_to_json(c)::text FROM pawbridge_community.comments c WHERE comment_id="
                + id + "),'null')");
        return mapper.readTree(json);
    }

    private JsonNode withoutIdentity(JsonNode row) {
        ObjectNode result = row.deepCopy(); result.remove("comment_id"); return result;
    }

    private String unrelatedState() throws SQLException {
        return scalar("SELECT json_build_object("
                + "'users',(SELECT COALESCE(json_agg(u ORDER BY user_id),'[]') FROM pawbridge_user.users u),"
                + "'posts',(SELECT COALESCE(json_agg(p ORDER BY post_id),'[]') FROM pawbridge_community.posts p),"
                + "'outbox',(SELECT COALESCE(json_agg(e ORDER BY outbox_id),'[]') FROM pawbridge_community.outbox_events e),"
                + "'search',(SELECT COALESCE(json_agg(s ORDER BY post_id),'[]') FROM pawbridge_community.post_search_documents s))");
    }

    private void audit(Comment entity, LocalDateTime modified) {
        AuditingHandler auditing = new AuditingHandler(new PersistentEntities(List.of()));
        auditing.setDateTimeProvider(() -> Optional.of(modified)); auditing.markModified(entity);
    }

    private boolean hasStaleCause(Throwable failure) {
        Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Throwable current = failure; current != null && seen.add(current); current = current.getCause()) {
            if (current instanceof org.hibernate.StaleStateException
                    || current instanceof jakarta.persistence.OptimisticLockException) return true;
        }
        return false;
    }

    private Connection admin() throws SQLException {
        Connection connection = owner.admin();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT 'f9',value,owner FROM pawbridge_f9_fixture.marker UNION ALL "
                        + "SELECT 'f10',value,owner FROM pawbridge_f10_fixture.marker ORDER BY 1")) {
            String expected = System.getenv("GITHUB_RUN_ID") + "-" + System.getenv("GITHUB_RUN_ATTEMPT");
            assertThat(System.getenv("F9_CI_OWNER")).isEqualTo(expected);
            assertThat(System.getenv("F10_CI_OWNER")).isEqualTo(expected);
            assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("f10");
            assertThat(rows.getString(2)).isEqualTo("pawbridge-f10-ci-only-v1"); assertThat(rows.getString(3)).isEqualTo(expected);
            assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("f9");
            assertThat(rows.getString(2)).isEqualTo("pawbridge-f9-ci-only-v1"); assertThat(rows.getString(3)).isEqualTo(expected);
            assertThat(rows.next()).isFalse();
        } catch (SQLException | RuntimeException | Error failure) { connection.close(); throw failure; }
        return connection;
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = admin(); var statement = connection.createStatement()) { statement.execute(sql); }
    }

    private String scalar(String sql) throws SQLException {
        try (Connection connection = admin(); var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue(); return result.getString(1);
        }
    }

    private static Calendar utc() { return Calendar.getInstance(TimeZone.getTimeZone("UTC")); }

    private TransactionTemplate transaction(javax.sql.DataSource source) {
        var tx = new TransactionTemplate(new JdbcTransactionManager(source)); tx.setTimeout(10); return tx;
    }

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

    @FunctionalInterface
    private interface SqlAction { void run() throws SQLException; }

    private static final class TwoReadClock extends Clock {
        private final ZoneId zone;
        private final ArrayDeque<Instant> times = new ArrayDeque<>();
        TwoReadClock(LocalDateTime deleted, LocalDateTime modified) {
            zone = ZoneId.systemDefault(); times.add(deleted.atZone(zone).toInstant()); times.add(modified.atZone(zone).toInstant());
        }
        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId other) {
            if (!zone.equals(other)) throw new IllegalArgumentException("Synthetic clock has one explicit zone");
            return this;
        }
        @Override public Instant instant() { return times.removeFirst(); }
    }
}
