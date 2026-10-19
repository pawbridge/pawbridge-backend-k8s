package com.pawbridge.backend.compatibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pawbridge.backend.community.internal.post.application.ListPosts;
import com.pawbridge.backend.community.internal.post.application.PostDetails;
import com.pawbridge.backend.community.internal.post.infrastructure.JdbcPostReader;
import com.pawbridge.backend.user.api.UserNicknameQuery;
import com.pawbridge.backend.user.internal.nickname.infrastructure.JdbcUserNicknameQuery;
import com.pawbridge.communityservice.domain.entity.Post;
import com.pawbridge.communityservice.domain.entity.StringListConverter;
import com.pawbridge.communityservice.domain.repository.PostRepository;
import com.pawbridge.communityservice.dto.response.PostResponse;
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

/** Actual pinned PostRepository runs against the same rows as the candidate list reader. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class PostgresqlPostListCompatibilityTest {
    private static final long POST = 9_600_000L;
    private static final long AUTHOR = 9_700_000L;
    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 1, 1, 0, 0, 0, 123_456_000);
    private static final String POST_COLUMNS = "post_id,author_id,title,content,board_type,image_urls,"
            + "deleted_at,created_at,updated_at";
    private final ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
    private final AtomicInteger postQueries = new AtomicInteger();
    private F2PostgresqlFixture fixture;
    private F3PostgresqlFixture ownedFixture;
    private HikariDataSource pool;
    private JdbcTemplate jdbc;
    private JdbcPostReader posts;
    private JdbcUserNicknameQuery nicknames;
    private SessionFactory legacy;

    @BeforeAll
    void openOwnedHostedFixtureAndOneConnectionReadOnlyPool() throws Exception {
        assertThat(System.getenv("GITHUB_ACTIONS")).isEqualTo("true");
        assertThat(System.getenv("RUNNER_ENVIRONMENT")).isEqualTo("github-hosted");
        assertThat(System.getenv("RUNNER_OS")).isEqualTo("Linux");
        assertThat(System.getenv("GITHUB_REPOSITORY")).isEqualTo("pawbridge/pawbridge-backend-k8s");
        assertThat(ZoneId.systemDefault().getId()).isIn("UTC", "Asia/Seoul");
        ownedFixture = F3PostgresqlFixture.open();
        fixture = ownedFixture.reads();
        // The existing owner/marker chain is reused; F6 adds no schema, pool, or grant.
        try (Connection admin = admin(); var statement = admin.createStatement();
                var rows = statement.executeQuery("SELECT value,owner FROM pawbridge_f5_fixture.marker")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo("pawbridge-f5-ci-only-v1");
            assertThat(rows.getString(2)).isEqualTo(System.getenv("GITHUB_RUN_ID") + "-"
                    + System.getenv("GITHUB_RUN_ATTEMPT"));
            assertThat(rows.next()).isFalse();
        }
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
        config.setPoolName("f6-single-connection-list");
        pool = new HikariDataSource(config);
        jdbc = new JdbcTemplate(pool);
        DataSourceTransactionManager manager = new DataSourceTransactionManager(pool);
        manager.setEnforceReadOnly(true);
        TransactionTemplate readOnly = new TransactionTemplate(manager);
        readOnly.setReadOnly(true);
        readOnly.setTimeout(5);
        JdbcOperations observed = (JdbcOperations) Proxy.newProxyInstance(
                JdbcOperations.class.getClassLoader(), new Class<?>[] {JdbcOperations.class},
                (proxy, method, arguments) -> {
                    if (method.getName().startsWith("query")) {
                        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                        assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
                        assertThat(jdbc.queryForObject("SHOW transaction_read_only", String.class)).isEqualTo("on");
                        if (((String) arguments[0]).contains("FROM pawbridge_community.posts")) {
                            postQueries.incrementAndGet();
                        }
                    }
                    try { return method.invoke(jdbc, arguments); }
                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
        posts = new JdbcPostReader(observed, readOnly, mapper);
        nicknames = new JdbcUserNicknameQuery(observed, readOnly);
        Configuration configuration = new Configuration().addAnnotatedClass(Post.class)
                .addAttributeConverter(StringListConverter.class)
                .setProperty(AvailableSettings.DEFAULT_SCHEMA, "pawbridge_community")
                .setProperty(AvailableSettings.HBM2DDL_AUTO, "none")
                .setProperty(AvailableSettings.JDBC_TIME_ZONE, "UTC")
                .setProperty(AvailableSettings.SHOW_SQL, "false");
        configuration.getProperties().put(AvailableSettings.JAKARTA_NON_JTA_DATASOURCE, pool);
        legacy = configuration.buildSessionFactory();
        returned();
    }

    @AfterEach
    void removeOnlyOwnedTestRows() throws SQLException {
        if (fixture != null) fixture.close();
        postQueries.set(0);
    }

    @AfterAll
    void closeOracleAndSingleConnectionPool() throws SQLException {
        try { if (legacy != null) legacy.close(); }
        finally {
            try { if (pool != null) pool.close(); }
            finally { if (fixture != null) fixture.close(); }
        }
    }

    @Test
    void givenNoPosts__whenListing__thenEmptyArrayWithoutNicknameCalls() {
        AtomicInteger calls = new AtomicInteger();
        assertThat(new ListPosts(posts, id -> {
            calls.incrementAndGet();
            throw new AssertionError("Empty list must not resolve nicknames");
        }).read()).isEmpty();
        assertThat(calls.get()).isZero();
        assertThat(postQueries.get()).isOne();
        returned();
    }

    @Test
    void givenFiveBoardsImagesTiesAndDecoys__whenListing__thenActualRepositoryAndNineFieldsAgree()
            throws Exception {
        fixture.insertUser(AUTHOR, "비교 닉네임");
        String[] boards = {"MISSING", "PROTECTION", "REPORT", "ADOPTION", "COMMUNICATION"};
        String[] images = {null, "null", "[null,\"한글\"]", "[\"두번째\",\"첫번째\",\"두번째\"]", "[]"};
        for (int index = 0; index < boards.length; index++) {
            fixture.insertPost(POST + index, AUTHOR, boards[index], images[index],
                    CREATED.minusHours(index / 2), CREATED.plusMinutes(1), null);
        }
        fixture.insertPost(POST + 5, AUTHOR, "REPORT", "[]", CREATED.plusDays(1), CREATED,
                CREATED.plusDays(2));
        fixture.insertPublicDecoys(POST, AUTHOR);

        List<PostResponse> oracle;
        try (var entityManager = legacy.createEntityManager()) {
            entityManager.getTransaction().begin();
            // No test-side repository declaration: Spring Data executes the extracted interface.
            PostRepository repository = new JpaRepositoryFactory(entityManager).getRepository(PostRepository.class);
            oracle = repository.findByDeletedAtIsNullOrderByCreatedAtDesc().stream()
                    .map(post -> PostResponse.fromEntity(post, "비교 닉네임")).toList();
            entityManager.getTransaction().commit();
        }
        returned();
        List<Long> authors = new ArrayList<>();
        List<PostDetails> candidate = new ListPosts(posts, id -> {
            returned();
            authors.add(id);
            return nicknames.findNickname(id);
        }).read();

        assertThat(postQueries.get()).isOne();
        assertThat(authors).containsExactly(AUTHOR, AUTHOR, AUTHOR, AUTHOR, AUTHOR);
        assertThat(candidate).hasSize(5).extracting(PostDetails::boardType)
                .containsExactlyInAnyOrder(boards);
        assertThat(candidate).extracting(PostDetails::createdAt).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(oracle).extracting(PostResponse::createdAt).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        Map<Long, JsonNode> actualById = candidate.stream().collect(Collectors.toMap(
                PostDetails::postId, mapper::valueToTree));
        Map<Long, JsonNode> oracleById = oracle.stream().collect(Collectors.toMap(
                PostResponse::postId, mapper::valueToTree));
        // Equal-time groups have equal membership and rows; their internal array order is unspecified.
        assertThat(actualById).isEqualTo(oracleById);
        assertThat(candidate.stream().collect(Collectors.groupingBy(PostDetails::createdAt,
                Collectors.mapping(PostDetails::postId, Collectors.toSet())))).isEqualTo(
                oracle.stream().collect(Collectors.groupingBy(PostResponse::createdAt,
                        Collectors.mapping(PostResponse::postId, Collectors.toSet()))));
        actualById.values().forEach(row -> assertThat(row.size()).isEqualTo(9));
        assertThat(actualById.get(POST).get("imageUrls")).isEmpty();
        assertThat(actualById.get(POST + 1).get("imageUrls").isNull()).isTrue();
        assertThat(actualById.get(POST).get("createdAt").asText()).isEqualTo(
                CREATED.atZone(ZoneOffset.UTC).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime().toString());
        returned();
    }

    @Test
    void givenMissingEmptyAndWhitespaceNicknames__whenListing__thenEachRowKeepsItsOwnPolicy()
            throws Exception {
        fixture.insertUser(AUTHOR, "");
        fixture.insertUser(AUTHOR + 1, "  ");
        for (int index = 0; index < 3; index++) {
            fixture.insertPost(POST + index, AUTHOR + index, "REPORT", "[]",
                    CREATED.minusHours(index), CREATED, null);
        }
        List<PostDetails> rows = new ListPosts(posts, nicknames).read();
        assertThat(rows).extracting(PostDetails::authorNickname)
                .containsExactly(null, "  ", "사용자" + (AUTHOR + 2));
        assertThat(postQueries.get()).isOne();
        returned();
    }

    @Test
    void givenNicknameSqlFailure__whenPermissionRecoversBetweenRows__thenOnlyFailedRowFallsBack()
            throws Exception {
        fixture.insertUser(AUTHOR, "회복 닉네임");
        fixture.insertPost(POST, AUTHOR, "REPORT", "[]", CREATED, CREATED, null);
        fixture.insertPost(POST + 1, AUTHOR, "REPORT", "[]", CREATED.minusHours(1), CREATED, null);
        AtomicInteger calls = new AtomicInteger();
        UserNicknameQuery recovering = id -> {
            returned();
            int call = calls.incrementAndGet();
            try { return nicknames.findNickname(id); }
            finally {
                if (call == 1) {
                    try { fixture.restoreNicknameSelect(); }
                    catch (SQLException failure) { throw new AssertionError(failure); }
                }
            }
        };
        fixture.revokeNicknameSelect();
        try {
            assertThat(new ListPosts(posts, recovering).read()).extracting(PostDetails::authorNickname)
                    .containsExactly("사용자" + AUTHOR, "회복 닉네임");
        } finally { fixture.restoreNicknameSelect(); }
        assertThat(calls.get()).isEqualTo(2);
        assertThat(new ListPosts(posts, nicknames).read()).extracting(PostDetails::authorNickname)
                .containsExactly("회복 닉네임", "회복 닉네임");
        returned();
    }

    @Test
    void givenPostSelectFailure__whenListingThenRestoring__thenFailurePropagatesAndNextReadRecovers()
            throws Exception {
        fixture.insertPost(POST, AUTHOR, "REPORT", "[]", CREATED, CREATED, null);
        AtomicInteger nicknameCalls = new AtomicInteger();
        ListPosts list = new ListPosts(posts, id -> {
            nicknameCalls.incrementAndGet();
            return nicknames.findNickname(id);
        });
        permission("REVOKE SELECT (" + POST_COLUMNS + ") ON pawbridge_community.posts FROM "
                + F2PostgresqlFixture.APPLICATION_ROLE);
        try {
            assertThatThrownBy(list::read).isInstanceOf(DataAccessException.class);
            assertThat(nicknameCalls.get()).isZero();
            returned();
        } finally {
            permission("GRANT SELECT (" + POST_COLUMNS + ") ON pawbridge_community.posts TO "
                    + F2PostgresqlFixture.APPLICATION_ROLE);
        }
        assertThat(list.read()).hasSize(1);
        returned();
    }

    @Test
    void givenInvalidImageJsonShape__whenListing__thenMappingFailureDoesNotBecomeEmptyList()
            throws Exception {
        fixture.insertPost(POST, AUTHOR, "REPORT", "{\"invalid\":true}", CREATED, CREATED, null);
        assertThatThrownBy(() -> new ListPosts(posts, nicknames).read())
                .isInstanceOf(IllegalStateException.class).hasMessage("Failed to read post image URLs");
        returned();
    }

    private Connection admin() throws SQLException {
        return ownedFixture.admin();
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
