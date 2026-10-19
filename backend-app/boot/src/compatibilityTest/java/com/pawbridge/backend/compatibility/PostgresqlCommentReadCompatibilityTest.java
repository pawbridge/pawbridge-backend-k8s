package com.pawbridge.backend.compatibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pawbridge.backend.community.internal.comment.application.CommentDetails;
import com.pawbridge.backend.community.internal.comment.application.ListComments;
import com.pawbridge.backend.community.internal.comment.infrastructure.JdbcCommentReader;
import com.pawbridge.backend.user.api.UserNicknameQuery;
import com.pawbridge.backend.user.internal.nickname.infrastructure.JdbcUserNicknameQuery;
import com.pawbridge.communityservice.domain.entity.Comment;
import com.pawbridge.communityservice.domain.repository.CommentRepository;
import com.pawbridge.communityservice.dto.response.CommentResponse;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
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
import org.springframework.dao.DataAccessException;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Compares the actual pinned repository and response on run-owned PostgreSQL rows. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class PostgresqlCommentReadCompatibilityTest {
    private static final long COMMENT = 9_820_000L;
    private static final long POST = 9_830_000L;
    private static final long AUTHOR = 9_840_000L;
    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 1, 2, 0, 0, 0, 123_456_000);
    private static final String COLUMNS = "comment_id,post_id,author_id,content,created_at,updated_at,deleted_at";
    private final ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
    private final AtomicInteger commentQueries = new AtomicInteger();
    private F3PostgresqlFixture owner;
    private F2PostgresqlFixture fixture;
    private HikariDataSource pool;
    private JdbcCommentReader comments;
    private JdbcUserNicknameQuery nicknames;
    private SessionFactory legacy;

    @BeforeAll
    void openOnlyOwnedHostedFixtureAndSingleConnectionReadOnlyPool() throws Exception {
        assertThat(System.getenv("GITHUB_ACTIONS")).isEqualTo("true");
        assertThat(System.getenv("RUNNER_ENVIRONMENT")).isEqualTo("github-hosted");
        assertThat(System.getenv("RUNNER_OS")).isEqualTo("Linux");
        assertThat(System.getenv("GITHUB_REPOSITORY")).isEqualTo("pawbridge/pawbridge-backend-k8s");
        assertThat(ZoneId.systemDefault().getId()).isIn("UTC", "Asia/Seoul");
        owner = F3PostgresqlFixture.open();
        fixture = owner.reads();
        try (Connection ignored = admin()) { }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(fixture.jdbcUrl());
        config.setUsername(fixture.applicationUser());
        config.setPassword(fixture.applicationPassword());
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(0);
        config.setReadOnly(true);
        config.setConnectionTimeout(2_000);
        config.setValidationTimeout(1_000);
        config.setConnectionInitSql("SET search_path TO public");
        config.setPoolName("f8-single-connection-comments");
        pool = new HikariDataSource(config);
        JdbcTemplate jdbc = new JdbcTemplate(pool);
        jdbc.setQueryTimeout(3);
        DataSourceTransactionManager manager = new DataSourceTransactionManager(pool);
        manager.setEnforceReadOnly(true);
        TransactionTemplate transaction = new TransactionTemplate(manager);
        transaction.setReadOnly(true);
        transaction.setTimeout(3);
        JdbcOperations observed = (JdbcOperations) Proxy.newProxyInstance(JdbcOperations.class.getClassLoader(),
                new Class<?>[] {JdbcOperations.class}, (proxy, method, arguments) -> {
                    if (method.getName().startsWith("query")) {
                        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                        assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
                        assertThat(jdbc.queryForObject("SHOW transaction_read_only", String.class)).isEqualTo("on");
                        if (((String) arguments[0]).contains("pawbridge_community.comments")) {
                            commentQueries.incrementAndGet();
                        }
                    }
                    try { return method.invoke(jdbc, arguments); }
                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
        comments = new JdbcCommentReader(observed, transaction);
        nicknames = new JdbcUserNicknameQuery(observed, transaction);
        Configuration configuration = new Configuration().addAnnotatedClass(Comment.class)
                .setProperty(AvailableSettings.DEFAULT_SCHEMA, "pawbridge_community")
                .setProperty(AvailableSettings.HBM2DDL_AUTO, "none")
                .setProperty(AvailableSettings.JDBC_TIME_ZONE, "UTC")
                .setProperty(AvailableSettings.SHOW_SQL, "false");
        configuration.getProperties().put(AvailableSettings.JAKARTA_NON_JTA_DATASOURCE, pool);
        legacy = configuration.buildSessionFactory();
        returned();
    }

    @AfterEach
    void removeOnlyThisTestsSyntheticRows() throws SQLException {
        if (owner != null) {
            try (Connection connection = admin(); var statement = connection.prepareStatement(
                    "DELETE FROM pawbridge_community.comments WHERE comment_id >= ? AND comment_id < ?")) {
                statement.setLong(1, COMMENT);
                statement.setLong(2, COMMENT + 1_000);
                statement.executeUpdate();
            }
        }
        if (fixture != null) fixture.close();
        commentQueries.set(0);
    }

    @AfterAll
    void closeLegacyAndReadPool() {
        try { if (legacy != null) legacy.close(); }
        finally { if (pool != null) pool.close(); }
    }

    @Test
    void givenAbsentPostAndNoComments__whenListing__thenEmptyWithoutNicknameCalls() {
        assertThat(new ListComments(comments, id -> {
            throw new AssertionError("An empty list must not query nicknames");
        }).read(POST)).isEmpty();
        assertThat(commentQueries.get()).isOne();
        returned();
    }

    @Test
    void givenDatesTiesDeletedAndOtherPostRows__whenListing__thenActualRepositoryAndSevenFieldsAgree()
            throws Exception {
        fixture.insertUser(AUTHOR, "비교 닉네임");
        fixture.insertPost(POST, AUTHOR, "REPORT", "[]", CREATED, CREATED, null);
        insertComment(COMMENT, POST, AUTHOR, "한글 \"본문\"\n줄바꿈", CREATED, null);
        insertComment(COMMENT + 1, POST, AUTHOR, "동률 댓글", CREATED, null);
        insertComment(COMMENT + 2, POST, AUTHOR, "오래된 댓글", CREATED.minusHours(2), null);
        insertComment(COMMENT + 3, POST, AUTHOR, "삭제 제외", CREATED.plusHours(1), CREATED);
        insertComment(COMMENT + 4, POST + 1, AUTHOR, "다른 게시글", CREATED.plusHours(2), null);

        List<CommentResponse> expected = legacyRead(POST, "비교 닉네임");
        List<Long> resolved = new ArrayList<>();
        List<CommentDetails> actual = new ListComments(comments, id -> {
            returned();
            resolved.add(id);
            return nicknames.findNickname(id);
        }).read(POST);
        assertThat(commentQueries.get()).isOne();
        assertThat(resolved).containsExactly(AUTHOR, AUTHOR, AUTHOR);
        assertThat(actual).hasSize(3).extracting(CommentDetails::createdAt)
                .isSortedAccordingTo(java.util.Comparator.reverseOrder());
        Map<Long, JsonNode> actualById = actual.stream().collect(Collectors.toMap(
                CommentDetails::commentId, mapper::valueToTree));
        Map<Long, JsonNode> expectedById = expected.stream().collect(Collectors.toMap(
                CommentResponse::commentId, mapper::valueToTree));
        assertThat(actualById).isEqualTo(expectedById);
        actualById.values().forEach(row -> assertThat(row.size()).isEqualTo(7));
        assertThat(actualById.get(COMMENT).get("createdAt").asText()).isEqualTo(
                CREATED.atZone(ZoneOffset.UTC).withZoneSameInstant(ZoneId.systemDefault())
                        .toLocalDateTime().toString());
        // Ties retain membership and values; an ID ordering guarantee is not introduced.
        assertThat(actual.stream().collect(Collectors.groupingBy(CommentDetails::createdAt,
                Collectors.mapping(CommentDetails::commentId, Collectors.toSet())))).isEqualTo(
                expected.stream().collect(Collectors.groupingBy(CommentResponse::createdAt,
                        Collectors.mapping(CommentResponse::commentId, Collectors.toSet()))));
        returned();
    }

    @Test
    void givenDeletedOrAbsentParentWithLiveComment__whenListing__thenNoNewParentFilter() throws Exception {
        fixture.insertPost(POST, AUTHOR, "REPORT", "[]", CREATED, CREATED, CREATED);
        insertComment(COMMENT, POST, AUTHOR, "삭제 게시글의 기존 댓글", CREATED, null);
        insertComment(COMMENT + 1, POST + 1, AUTHOR, "부모 없는 기존 댓글", CREATED, null);
        for (long postId : List.of(POST, POST + 1)) {
            List<CommentDetails> actual = new ListComments(comments, nicknames).read(postId);
            assertThat(actual).hasSize(1);
            assertThat(mapper.<JsonNode>valueToTree(actual.getFirst())).isEqualTo(
                    mapper.valueToTree(legacyRead(postId, "사용자" + AUTHOR).getFirst()));
            returned();
        }
    }

    @Test
    void givenMissingEmptyAndWhitespaceNicknames__whenListing__thenEachCommentKeepsDefinedPolicy()
            throws Exception {
        fixture.insertUser(AUTHOR, "");
        fixture.insertUser(AUTHOR + 1, "  ");
        for (int index = 0; index < 3; index++) {
            insertComment(COMMENT + index, POST, AUTHOR + index, "댓글", CREATED.minusHours(index), null);
        }
        assertThat(new ListComments(comments, nicknames).read(POST)).extracting(CommentDetails::authorNickname)
                .containsExactly(null, "  ", "사용자" + (AUTHOR + 2));
        assertThat(commentQueries.get()).isOne();
        returned();
    }

    @Test
    void givenNicknameSelectFailure__whenPermissionRecoversBetweenComments__thenOnlyFailedCommentFallsBack()
            throws Exception {
        fixture.insertUser(AUTHOR, "회복 닉네임");
        insertComment(COMMENT, POST, AUTHOR, "첫 댓글", CREATED, null);
        insertComment(COMMENT + 1, POST, AUTHOR, "다음 댓글", CREATED.minusHours(1), null);
        AtomicInteger calls = new AtomicInteger();
        UserNicknameQuery recovering = id -> {
            returned();
            try {
                if (calls.getAndIncrement() == 0) permission(
                        "REVOKE SELECT(nickname) ON pawbridge_user.users FROM pawbridge_f2_reader");
                else permission("GRANT SELECT(nickname) ON pawbridge_user.users TO pawbridge_f2_reader");
            } catch (SQLException failure) { throw new IllegalStateException(failure); }
            return nicknames.findNickname(id);
        };
        try {
            assertThat(new ListComments(comments, recovering).read(POST))
                    .extracting(CommentDetails::authorNickname).containsExactly("사용자" + AUTHOR, "회복 닉네임");
        } finally {
            permission("GRANT SELECT(nickname) ON pawbridge_user.users TO pawbridge_f2_reader");
        }
        assertThat(calls.get()).isEqualTo(2);
        returned();
    }

    @Test
    void givenCommentSelectFailure__whenPermissionRecovers__thenFailureIsNotEmptySuccessAndPoolRecovers()
            throws Exception {
        insertComment(COMMENT, POST, AUTHOR, "복구 댓글", CREATED, null);
        permission("REVOKE SELECT(" + COLUMNS + ") ON pawbridge_community.comments FROM pawbridge_f2_reader");
        try {
            assertThatThrownBy(() -> new ListComments(comments, id -> {
                throw new AssertionError("Failed comment SQL must not query nicknames");
            }).read(POST)).isInstanceOf(DataAccessException.class);
            returned();
        } finally {
            permission("GRANT SELECT(" + COLUMNS + ") ON pawbridge_community.comments TO pawbridge_f2_reader");
        }
        assertThat(new ListComments(comments, nicknames).read(POST)).hasSize(1);
        returned();
    }

    private List<CommentResponse> legacyRead(long postId, String nickname) {
        try (var entityManager = legacy.createEntityManager()) {
            entityManager.getTransaction().begin();
            CommentRepository repository = new JpaRepositoryFactory(entityManager).getRepository(CommentRepository.class);
            List<CommentResponse> result = repository.findByPostIdAndDeletedAtIsNullOrderByCreatedAtDesc(postId)
                    .stream().map(comment -> CommentResponse.fromEntity(comment, nickname)).toList();
            entityManager.getTransaction().commit();
            return result;
        }
    }

    private void insertComment(long commentId, long postId, long authorId, String content,
            LocalDateTime createdAt, LocalDateTime deletedAt) throws SQLException {
        try (Connection connection = admin(); var statement = connection.prepareStatement(
                "INSERT INTO pawbridge_community.comments (comment_id,post_id,author_id,content,created_at,updated_at,deleted_at) "
                + "VALUES (?,?,?,?,?,?,?)")) {
            statement.setLong(1, commentId);
            statement.setLong(2, postId);
            statement.setLong(3, authorId);
            statement.setString(4, content);
            statement.setObject(5, createdAt);
            statement.setObject(6, createdAt.plusMinutes(1));
            statement.setObject(7, deletedAt);
            statement.executeUpdate();
        }
    }

    private Connection admin() throws SQLException {
        Connection connection = owner.admin();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT value,owner FROM pawbridge_f8_fixture.marker")) {
            String expected = System.getenv("GITHUB_RUN_ID") + "-" + System.getenv("GITHUB_RUN_ATTEMPT");
            assertThat(System.getenv("F8_CI_OWNER")).isEqualTo(expected);
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo("pawbridge-f8-ci-only-v1");
            assertThat(rows.getString(2)).isEqualTo(expected);
            assertThat(rows.next()).isFalse();
        } catch (RuntimeException | Error | SQLException failure) {
            connection.close();
            throw failure;
        }
        return connection;
    }

    private void permission(String sql) throws SQLException {
        try (Connection connection = admin(); var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void returned() {
        assertThat(pool.getMaximumPoolSize()).isOne();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TransactionSynchronizationManager.hasResource(pool)).isFalse();
        assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
    }
}
