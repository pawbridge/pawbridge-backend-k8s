package com.pawbridge.backend.compatibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pawbridge.backend.community.internal.post.application.PostDetails;
import com.pawbridge.backend.community.internal.post.application.ReadPost;
import com.pawbridge.backend.community.internal.post.infrastructure.JdbcPostReader;
import com.pawbridge.backend.user.api.UserNicknameQuery;
import com.pawbridge.backend.user.internal.nickname.infrastructure.JdbcUserNicknameQuery;
import com.pawbridge.communityservice.domain.entity.BoardType;
import com.pawbridge.communityservice.domain.entity.Post;
import com.pawbridge.communityservice.domain.entity.StringListConverter;
import com.pawbridge.communityservice.dto.response.PostResponse;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.hibernate.Session;
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
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Uses the exact legacy entities/converter/DTO extracted from the pinned source SHA. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class PostgresqlPostReadCompatibilityTest {
    private static final long POST_ID = 9_100_001L;
    private static final long AUTHOR_ID = 9_200_001L;
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2025, 12, 31, 23, 59, 59);
    private static final LocalDateTime UPDATED_AT = LocalDateTime.of(2026, 1, 1, 0, 0, 0, 123_456_000);

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    private final AtomicInteger observedReads = new AtomicInteger();
    private F2PostgresqlFixture fixture;
    private HikariDataSource pool;
    private JdbcTemplate jdbc;
    private JdbcPostReader posts;
    private JdbcUserNicknameQuery nicknames;
    private SessionFactory legacySessionFactory;

    @BeforeAll
    void openGuardedFixtureAndSingleConnectionPool() throws SQLException {
        assertThat(ZoneId.systemDefault().getId()).isIn("UTC", "Asia/Seoul");
        fixture = F2PostgresqlFixture.open();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(fixture.jdbcUrl());
        config.setUsername(fixture.applicationUser());
        config.setPassword(fixture.applicationPassword());
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(2_000);
        config.setValidationTimeout(1_000);
        config.setConnectionInitSql("SET search_path TO public");
        config.setPoolName("f2-single-connection-compatibility");
        pool = new HikariDataSource(config);
        jdbc = new JdbcTemplate(pool);
        DataSourceTransactionManager manager = new DataSourceTransactionManager(pool);
        manager.setEnforceReadOnly(true);
        TransactionTemplate readOnly = new TransactionTemplate(manager);
        readOnly.setReadOnly(true);
        readOnly.setTimeout(5);
        JdbcOperations observedJdbc = observeReadOnlyQueries(jdbc);
        posts = new JdbcPostReader(observedJdbc, readOnly, objectMapper);
        nicknames = new JdbcUserNicknameQuery(observedJdbc, readOnly);

        Configuration legacy = new Configuration()
                .addAnnotatedClass(Post.class)
                .addAttributeConverter(StringListConverter.class)
                .setProperty(AvailableSettings.DEFAULT_SCHEMA, "pawbridge_community")
                .setProperty(AvailableSettings.HBM2DDL_AUTO, "none")
                .setProperty(AvailableSettings.JDBC_TIME_ZONE, "UTC")
                .setProperty(AvailableSettings.SHOW_SQL, "false");
        legacy.getProperties().put(AvailableSettings.JAKARTA_NON_JTA_DATASOURCE, pool);
        legacySessionFactory = legacy.buildSessionFactory();
        try (var connection = pool.getConnection()) {
            System.out.printf("F2 oracle: Hibernate=%s pgjdbc=%s jdbc.time_zone=UTC JVM=%s%n",
                    org.hibernate.Version.getVersionString(),
                    connection.getMetaData().getDriverVersion(), ZoneId.systemDefault());
        }
        assertConnectionsReturned();
    }

    @AfterEach
    void removeOnlyRowsCreatedByThisTest() throws SQLException {
        if (fixture != null) fixture.close();
    }

    @AfterAll
    void closeOracleAndPool() throws SQLException {
        try {
            if (legacySessionFactory != null) legacySessionFactory.close();
        } finally {
            try {
                if (pool != null) pool.close();
            } finally {
                if (fixture != null) fixture.close();
            }
        }
    }

    @Test
    void givenPublicDecoys__whenReadingPost__thenBothAdaptersUseTheirOwnedSchemas() throws Exception {
        fixture.insertUser(AUTHOR_ID, "원본 닉네임");
        insertPost("COMMUNICATION", "[]", null);
        fixture.insertPublicDecoys(POST_ID, AUTHOR_ID);

        PostDetails response = new ReadPost(posts, nicknames).read(POST_ID);

        assertThat(response.title()).isEqualTo("원본 제목");
        assertThat(response.authorNickname()).isEqualTo("원본 닉네임");
        assertThat(response.boardType()).isEqualTo("COMMUNICATION");
        assertConnectionsReturned();
    }

    @Test
    void givenMissingOrSoftDeletedPost__whenReadingProjection__thenNoPostIsReturned() throws Exception {
        assertThat(posts.findById(POST_ID)).isEmpty();
        insertPost("MISSING", "[]", UPDATED_AT);

        assertThat(posts.findById(POST_ID)).isEmpty();
        assertConnectionsReturned();
    }

    @Test
    void givenMissingAuthor__whenReadingStoredPost__thenDisplayFallbackIsReturned() throws Exception {
        insertPost("PROTECTION", "[]", null);

        assertThat(new ReadPost(posts, nicknames).read(POST_ID).authorNickname())
                .isEqualTo("사용자" + AUTHOR_ID);
        assertConnectionsReturned();
    }

    @Test
    void givenPoolSizeOne__whenCombiningPostAndAuthor__thenPostTransactionEndsBeforeUserQuery()
            throws Exception {
        fixture.insertUser(AUTHOR_ID, "한 연결 닉네임");
        insertPost("ADOPTION", "[]", null);
        UserNicknameQuery observingUser = authorId -> {
            assertThat(authorId).isEqualTo(AUTHOR_ID);
            assertConnectionsReturned();
            return nicknames.findNickname(authorId);
        };

        assertThat(new ReadPost(posts, observingUser).read(POST_ID).authorNickname())
                .isEqualTo("한 연결 닉네임");
        assertThat(pool.getMaximumPoolSize()).isOne();
        assertConnectionsReturned();
    }

    @Test
    void givenNicknameSelectRevoked__whenReadingThenRestoringPermission__thenFallbackAndNextReadRecover()
            throws Exception {
        fixture.insertUser(AUTHOR_ID, "복구된 닉네임");
        insertPost("REPORT", "[]", null);
        ReadPost readPost = new ReadPost(posts, nicknames);
        fixture.revokeNicknameSelect();
        try {
            assertThat(readPost.read(POST_ID).authorNickname()).isEqualTo("사용자" + AUTHOR_ID);
            assertConnectionsReturned();
        } finally {
            fixture.restoreNicknameSelect();
        }

        assertThat(readPost.read(POST_ID).authorNickname()).isEqualTo("복구된 닉네임");
        assertConnectionsReturned();
    }

    @Test
    void givenSelectOnlyApplicationRole__whenTryingWrites__thenPostUserAndDdlWritesAreDenied()
            throws Exception {
        fixture.insertUser(AUTHOR_ID, "읽기 전용");
        insertPost("COMMUNICATION", "[]", null);
        assertThat(new ReadPost(posts, nicknames).read(POST_ID).authorNickname()).isEqualTo("읽기 전용");
        assertThat(observedReads.get()).isPositive();

        assertPermissionDenied(() -> jdbc.update(
                "UPDATE pawbridge_community.posts SET title = title WHERE post_id = ?", POST_ID));
        assertPermissionDenied(() -> jdbc.update(
                "INSERT INTO pawbridge_user.users (user_id, nickname) VALUES (?, ?)",
                AUTHOR_ID + 1, "쓰기 금지"));
        assertPermissionDenied(() -> jdbc.execute(
                "CREATE TABLE pawbridge_community.f2_forbidden_write (id BIGINT)"));
        assertConnectionsReturned();
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("legacyMappingCases")
    void givenSameStoredRow__whenReadingLegacyAndCandidate__thenJsonAndLocalDateTimeAreEqual(
            String boardType, String caseName, String imageUrls) throws Exception {
        fixture.insertUser(AUTHOR_ID, "비교 닉네임");
        insertPost(boardType, imageUrls, null);

        PostResponse legacy;
        try (Session session = legacySessionFactory.openSession()) {
            session.setDefaultReadOnly(true);
            var transaction = session.beginTransaction();
            legacy = PostResponse.fromEntity(session.find(Post.class, POST_ID), "비교 닉네임");
            transaction.commit();
        }
        assertConnectionsReturned();
        PostDetails candidate = new ReadPost(posts, nicknames).read(POST_ID);

        JsonNode candidateJson = objectMapper.valueToTree(candidate);
        JsonNode legacyJson = objectMapper.valueToTree(legacy);
        assertThat(candidateJson)
                .as("stored row parity: %s / %s / JVM %s", boardType, caseName, ZoneId.systemDefault())
                .isEqualTo(legacyJson);
        assertThat(candidate.createdAt()).isEqualTo(legacy.createdAt());
        assertThat(candidate.updatedAt()).isEqualTo(legacy.updatedAt());
        assertConnectionsReturned();
    }

    private static Stream<Arguments> legacyMappingCases() {
        Stream<Arguments> boards = Arrays.stream(BoardType.values())
                .map(board -> Arguments.of(board.name(), "empty array", "[]"));
        return Stream.concat(boards, Stream.of(
                Arguments.of("COMMUNICATION", "SQL NULL", null),
                Arguments.of("COMMUNICATION", "JSON null", "null"),
                Arguments.of("COMMUNICATION", "null array element", "[null,\"https://example.test/강아지.jpg\"]"),
                Arguments.of("COMMUNICATION", "ordered duplicates", "[\"두번째\",\"첫번째\",\"두번째\"]")));
    }

    private void insertPost(String boardType, String images, LocalDateTime deletedAt) throws SQLException {
        fixture.insertPost(POST_ID, AUTHOR_ID, boardType, images, CREATED_AT, UPDATED_AT, deletedAt);
    }

    private JdbcOperations observeReadOnlyQueries(JdbcTemplate delegate) {
        return (JdbcOperations) Proxy.newProxyInstance(JdbcOperations.class.getClassLoader(),
                new Class<?>[] {JdbcOperations.class}, (proxy, method, arguments) -> {
                    if (method.getName().startsWith("query")) {
                        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                        assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
                        assertThat(delegate.queryForObject("SHOW transaction_read_only", String.class))
                                .isEqualTo("on");
                        observedReads.incrementAndGet();
                    }
                    try {
                        return method.invoke(delegate, arguments);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    private void assertConnectionsReturned() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TransactionSynchronizationManager.hasResource(pool)).isFalse();
        assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
    }

    private static void assertPermissionDenied(org.assertj.core.api.ThrowableAssert.ThrowingCallable write) {
        assertThatThrownBy(write).isInstanceOf(DataAccessException.class)
                .satisfies(failure -> assertThat(sqlState(failure)).isEqualTo("42501"));
    }

    private static String sqlState(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) return sql.getSQLState();
        }
        throw new AssertionError("Expected a real PostgreSQL SQLSTATE", failure);
    }
}
