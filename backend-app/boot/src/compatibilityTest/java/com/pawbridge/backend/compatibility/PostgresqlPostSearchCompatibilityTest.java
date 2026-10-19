package com.pawbridge.backend.compatibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pawbridge.backend.community.internal.post.application.PostDetails;
import com.pawbridge.backend.community.internal.post.application.PostSearchUnavailableException;
import com.pawbridge.backend.community.internal.post.application.SearchPosts;
import com.pawbridge.backend.community.internal.post.infrastructure.JdbcPostSearchReader;
import com.pawbridge.backend.user.api.UserNicknameQuery;
import com.pawbridge.backend.user.internal.nickname.infrastructure.JdbcUserNicknameQuery;
import com.pawbridge.communityservice.client.UserServiceClient;
import com.pawbridge.communityservice.exception.SearchServiceUnavailableException;
import com.pawbridge.communityservice.search.KoreanSearchTerms;
import com.pawbridge.communityservice.service.PostgresqlSearchService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Runs the hash-pinned production legacy search service, never a test copy of its SELECT. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class PostgresqlPostSearchCompatibilityTest {
    private static final long POST = 9_800_000L;
    private static final long AUTHOR = 9_900_000L;
    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 1, 2, 3, 4, 5, 123_456_000);
    private static final String SEARCH_COLUMNS = "post_id,title_hash,content_hash,analyzer_version,tokens";
    private final ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
    private final AtomicInteger searchQueries = new AtomicInteger();
    private final List<Long> candidateAuthors = new ArrayList<>();
    private F3PostgresqlFixture owned;
    private F2PostgresqlFixture fixture;
    private HikariDataSource pool;
    private JdbcTemplate jdbc;
    private JdbcUserNicknameQuery nicknames;
    private JdbcPostSearchReader reader;
    private SearchPosts candidate;
    private KoreanSearchTerms legacyTerms;
    private PostgresqlSearchService oracle;

    @BeforeAll
    void openSameOwnedFixtureWithOneReadOnlyConnectionAndActualLegacySearch() throws Exception {
        assertThat(ZoneId.systemDefault().getId()).isIn("UTC", "Asia/Seoul");
        owned = F3PostgresqlFixture.open();
        fixture = owned.reads();
        try (var admin = owned.admin(); var statement = admin.createStatement();
                var rows = statement.executeQuery("SELECT value,owner FROM pawbridge_f5_fixture.marker")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo("pawbridge-f5-ci-only-v1");
            assertThat(rows.getString(2)).isEqualTo(System.getenv("F3_CI_OWNER"));
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
        config.setPoolName("f7-single-connection-search");
        pool = new HikariDataSource(config);
        jdbc = new JdbcTemplate(pool);
        jdbc.setQueryTimeout(5);
        DataSourceTransactionManager manager = new DataSourceTransactionManager(pool);
        manager.setEnforceReadOnly(true);
        TransactionTemplate searchTransaction = new TransactionTemplate(manager);
        searchTransaction.setReadOnly(true);
        searchTransaction.setTimeout(5);
        JdbcOperations observed = (JdbcOperations) Proxy.newProxyInstance(JdbcOperations.class.getClassLoader(),
                new Class<?>[] {JdbcOperations.class}, (proxy, method, arguments) -> {
                    if (method.getName().startsWith("query")) {
                        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                        assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
                        assertThat(jdbc.queryForObject("SHOW transaction_read_only", String.class)).isEqualTo("on");
                        searchQueries.incrementAndGet();
                    }
                    try { return method.invoke(jdbc, arguments); }
                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
        reader = new JdbcPostSearchReader(observed, searchTransaction, mapper);
        TransactionTemplate nicknameTransaction = new TransactionTemplate(manager);
        nicknameTransaction.setReadOnly(true);
        nicknameTransaction.setTimeout(3);
        nicknames = new JdbcUserNicknameQuery(new JdbcTemplate(pool), nicknameTransaction);
        UserNicknameQuery observing = id -> {
            returned();
            candidateAuthors.add(id);
            return nicknames.findNickname(id);
        };
        candidate = new SearchPosts(reader, observing);
        legacyTerms = new KoreanSearchTerms();
        UserServiceClient legacyUsers = mock(UserServiceClient.class);
        when(legacyUsers.getUserNickname(anyLong())).thenAnswer(invocation -> {
            returned();
            String nickname = nicknames.findNickname(invocation.getArgument(0, Long.class))
                    .orElseThrow(() -> new IllegalStateException("Synthetic missing legacy user"));
            return nickname.isEmpty() ? null : nickname;
        });
        // The actual legacy SQL is unqualified. Scope only its borrowed connection,
        // restoring public before returning it; the candidate must qualify its schemas.
        DelegatingDataSource legacySource = new DelegatingDataSource(pool) {
            @Override public Connection getConnection() throws SQLException {
                Connection connection = super.getConnection();
                try { connection.setSchema("pawbridge_community"); }
                catch (SQLException failure) { connection.close(); throw failure; }
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                        new Class<?>[] {Connection.class}, (proxy, method, arguments) -> {
                            if (method.getName().equals("close")) {
                                try { connection.setSchema("public"); }
                                finally { connection.close(); }
                                return null;
                            }
                            try { return method.invoke(connection, arguments); }
                            catch (InvocationTargetException failure) { throw failure.getCause(); }
                        });
            }
        };
        oracle = new PostgresqlSearchService(legacySource, legacyTerms, legacyUsers, mapper);
        returned();
    }

    @AfterEach
    void removeOnlyOwnedRowsAndResetObservations() throws SQLException {
        if (fixture != null) fixture.close(); // projection rows cascade with these exact post IDs.
        searchQueries.set(0);
        candidateAuthors.clear();
    }

    @AfterAll
    void closeBothAnalyzersAndTheSharedPool() throws Exception {
        try { if (reader != null) reader.close(); }
        finally {
            try { if (legacyTerms != null) legacyTerms.close(); }
            finally {
                try { if (pool != null) pool.close(); }
                finally { if (fixture != null) fixture.close(); }
            }
        }
    }

    @Test
    void givenRankedRowsAcrossBoardsWithTies__whenSearching__thenActualLegacyTopTenAndNineFieldsAgree()
            throws Exception {
        fixture.insertUser(AUTHOR, "검색 닉네임");
        String[] boards = {"MISSING", "PROTECTION", "REPORT", "ADOPTION", "COMMUNICATION"};
        String[] images = {null, "null", "[null,\"한글\"]", "[\"둘\",\"하나\",\"둘\"]", "[]"};
        for (int index = 0; index < 13; index++) {
            insert(POST + index, AUTHOR, boards[index % 5], index < 3 ? "강아지" : "기록",
                    index < 3 ? "기록" : "강아지", images[index % 5],
                    index == 1 || index == 2 ? CREATED.plusHours(1) : CREATED.minusHours(index), null, true);
        }
        insert(POST + 13, AUTHOR, "REPORT", "강아지", "강아지", "[]", CREATED.plusDays(1), CREATED, false);
        fixture.insertPublicDecoys(POST, AUTHOR);
        String before = businessSnapshot();
        List<PostDetails> actual = candidate.search("강아지");

        assertThat(searchQueries.get()).isOne();
        assertThat(actual).hasSize(10);
        assertThat(actual).extracting(PostDetails::postId)
                .containsExactly(POST + 2, POST + 1, POST, POST + 3, POST + 4, POST + 5,
                        POST + 6, POST + 7, POST + 8, POST + 9);
        assertThat(actual).extracting(PostDetails::boardType).contains(boards);
        assertThat(candidateAuthors).hasSize(10).containsOnly(AUTHOR);
        assertThat(mapper.<JsonNode>valueToTree(actual)).isEqualTo(mapper.valueToTree(oracle.searchPosts("강아지")));
        actual.forEach(row -> assertThat(mapper.<JsonNode>valueToTree(row).size()).isEqualTo(9));
        // Search's JDBC LocalDateTime extraction deliberately differs from F2/F6 in Seoul.
        assertThat(actual.get(0).createdAt()).isEqualTo(CREATED.plusHours(1));
        assertThat(actual.get(0).updatedAt()).isEqualTo(CREATED.plusMinutes(1));
        assertThat(businessSnapshot()).isEqualTo(before);
        returned();
    }

    @ParameterizedTest
    @ValueSource(strings = {"강아지 고양이", "강아지 강아지 고양이", "강아지' \\ 고양이"})
    void givenAnalyzedOrInput__whenExecutingActualLegacyAndCandidate__thenBothTermsAndOrderingAgree(String keyword)
            throws Exception {
        insert(POST, AUTHOR, "REPORT", "강아지", "첫 기록", "[]", CREATED, null, true);
        insert(POST + 1, AUTHOR, "ADOPTION", "고양이", "다음 기록", "[]", CREATED, null, true);
        List<PostDetails> actual = candidate.search(keyword);
        assertThat(actual).extracting(PostDetails::postId).containsExactly(POST + 1, POST);
        assertThat(mapper.<JsonNode>valueToTree(actual)).isEqualTo(mapper.valueToTree(oracle.searchPosts(keyword)));
        assertThat(searchQueries.get()).isOne();
        returned();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "title_hash", "content_hash", "analyzer_version"})
    void givenNonmatchingStaleRow__whenSearchingThenDeletingOrRepairing__thenGlobalReadinessAndRecoveryAgree(
            String fault) throws Exception {
        insert(POST, AUTHOR, "REPORT", "강아지", "기록", "[]", CREATED, null, true);
        insert(POST + 1, AUTHOR, "ADOPTION", "고양이", "기록", "[]", CREATED, null, true);
        if (fault.equals("missing")) {
            owned.execute("DELETE FROM pawbridge_community.post_search_documents WHERE post_id=" + (POST + 1));
        } else {
            owned.execute("UPDATE pawbridge_community.post_search_documents SET " + fault
                    + "='stale' WHERE post_id=" + (POST + 1));
        }
        String before = businessSnapshot();
        assertThatThrownBy(() -> candidate.search("강아지")).isInstanceOf(PostSearchUnavailableException.class);
        assertThatThrownBy(() -> oracle.searchPosts("강아지")).isInstanceOf(SearchServiceUnavailableException.class);
        assertThat(candidateAuthors).isEmpty();
        assertThat(businessSnapshot()).isEqualTo(before);
        returned();
        owned.execute("UPDATE pawbridge_community.posts SET deleted_at=created_at WHERE post_id=" + (POST + 1));
        assertThat(candidate.search("강아지")).hasSize(1);
        assertThat(mapper.<JsonNode>valueToTree(candidate.search("강아지")))
                .isEqualTo(mapper.valueToTree(oracle.searchPosts("강아지")));
        owned.execute("UPDATE pawbridge_community.posts SET deleted_at=NULL WHERE post_id=" + (POST + 1));
        project(POST + 1, "고양이", "기록");
        assertThat(mapper.<JsonNode>valueToTree(candidate.search("강아지 고양이")))
                .isEqualTo(mapper.valueToTree(oracle.searchPosts("강아지 고양이")));
        returned();
    }

    @Test
    void givenProjectionSelectRevoked__whenSearchingThenRestoring__thenBothServicesFailUnavailableAndRecover()
            throws Exception {
        insert(POST, AUTHOR, "REPORT", "강아지", "기록", "[]", CREATED, null, true);
        owned.execute("REVOKE SELECT(tokens) ON pawbridge_community.post_search_documents FROM "
                + F2PostgresqlFixture.APPLICATION_ROLE);
        try {
            assertThatThrownBy(() -> candidate.search("강아지")).isInstanceOf(PostSearchUnavailableException.class);
            assertThatThrownBy(() -> oracle.searchPosts("강아지")).isInstanceOf(SearchServiceUnavailableException.class);
            assertThat(candidateAuthors).isEmpty();
            returned();
        } finally {
            owned.execute("GRANT SELECT(tokens) ON pawbridge_community.post_search_documents TO "
                    + F2PostgresqlFixture.APPLICATION_ROLE);
        }
        assertThat(mapper.<JsonNode>valueToTree(candidate.search("강아지")))
                .isEqualTo(mapper.valueToTree(oracle.searchPosts("강아지")));
        returned();
    }

    @Test
    void givenMissingEmptyAndSpaceNicknames__whenSearchingThroughSqlFailureAndRecovery__thenRowPoliciesAgree()
            throws Exception {
        fixture.insertUser(AUTHOR, "");
        fixture.insertUser(AUTHOR + 1, "  ");
        for (int index = 0; index < 4; index++) {
            insert(POST + index, AUTHOR + (index % 3), "REPORT", "강아지", "기록", "[]",
                    CREATED.minusHours(index), null, true);
        }
        List<PostDetails> normal = candidate.search("강아지");
        assertThat(normal).extracting(PostDetails::authorNickname)
                .containsExactly(null, "  ", "사용자" + (AUTHOR + 2), null);
        assertThat(candidateAuthors).containsExactly(AUTHOR, AUTHOR + 1, AUTHOR + 2, AUTHOR);
        assertThat(mapper.<JsonNode>valueToTree(normal)).isEqualTo(mapper.valueToTree(oracle.searchPosts("강아지")));
        fixture.revokeNicknameSelect();
        try {
            List<PostDetails> fallback = candidate.search("강아지");
            assertThat(fallback).extracting(PostDetails::authorNickname)
                    .containsExactly("사용자" + AUTHOR, "사용자" + (AUTHOR + 1),
                            "사용자" + (AUTHOR + 2), "사용자" + AUTHOR);
            assertThat(mapper.<JsonNode>valueToTree(fallback)).isEqualTo(mapper.valueToTree(oracle.searchPosts("강아지")));
            returned();
        } finally { fixture.restoreNicknameSelect(); }
        assertThat(candidate.search("강아지")).isEqualTo(normal);
        returned();
    }

    @Test
    void givenInvalidStoredImageShape__whenSearching__thenMappingFailureStaysOutsideUnavailableFallback()
            throws Exception {
        insert(POST, AUTHOR, "REPORT", "강아지", "기록", "{\"invalid\":true}", CREATED, null, true);
        assertThatThrownBy(() -> candidate.search("강아지")).isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(PostSearchUnavailableException.class);
        assertThatThrownBy(() -> oracle.searchPosts("강아지")).isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(SearchServiceUnavailableException.class);
        assertThat(candidateAuthors).isEmpty();
        returned();
    }

    @Test
    void givenExactFiveColumnSelect__whenReaderAttemptsProjectionWritesOrDdl__thenPostgresqlDeniesThem()
            throws Exception {
        try (Connection connection = DriverManager.getConnection(fixture.jdbcUrl(),
                fixture.applicationUser(), fixture.applicationPassword()); var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT has_table_privilege(current_user,"
                    + "'pawbridge_community.post_search_documents','SELECT'),"
                    + "bool_and(has_column_privilege(current_user,'pawbridge_community.post_search_documents',"
                    + "column_name,'SELECT')) FROM information_schema.columns WHERE table_schema='pawbridge_community'"
                    + " AND table_name='post_search_documents' AND column_name IN ('post_id','title_hash',"
                    + "'content_hash','analyzer_version','tokens')")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getBoolean(1)).isFalse();
                assertThat(rows.getBoolean(2)).isTrue();
            }
            statement.executeQuery("SELECT " + SEARCH_COLUMNS + " FROM pawbridge_community.post_search_documents").close();
            for (String sql : List.of(
                    "INSERT INTO pawbridge_community.post_search_documents(post_id) VALUES(0)",
                    "UPDATE pawbridge_community.post_search_documents SET title_hash='forbidden' WHERE false",
                    "DELETE FROM pawbridge_community.post_search_documents WHERE false",
                    "CREATE TABLE pawbridge_community.f7_forbidden(id BIGINT)")) {
                assertThatThrownBy(() -> statement.execute(sql)).isInstanceOf(SQLException.class)
                        .satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo("42501"));
            }
        }
        returned();
    }

    private void insert(long id, long author, String board, String title, String content, String images,
            LocalDateTime created, LocalDateTime deleted, boolean projection) throws SQLException {
        fixture.insertPost(id, author, board, images, created, CREATED.plusMinutes(1), deleted);
        try (var connection = owned.admin(); var statement = connection.prepareStatement(
                "UPDATE pawbridge_community.posts SET title=?,content=? WHERE post_id=?")) {
            statement.setString(1, title); statement.setString(2, content); statement.setLong(3, id);
            statement.executeUpdate();
        }
        if (projection) project(id, title, content);
    }

    private void project(long id, String title, String content) throws SQLException {
        try (var connection = owned.admin(); var statement = connection.prepareStatement("""
                INSERT INTO pawbridge_community.post_search_documents(post_id,title_hash,content_hash,analyzer_version,tokens)
                VALUES(?,md5(?),md5(?),?,setweight(to_tsvector('simple',?),'A')||setweight(to_tsvector('simple',?),'B'))
                ON CONFLICT(post_id) DO UPDATE SET title_hash=excluded.title_hash,content_hash=excluded.content_hash,
                analyzer_version=excluded.analyzer_version,tokens=excluded.tokens
                """)) {
            statement.setLong(1, id); statement.setString(2, title); statement.setString(3, content);
            statement.setString(4, KoreanSearchTerms.VERSION); statement.setString(5, legacyTerms.document(title));
            statement.setString(6, legacyTerms.document(content)); statement.executeUpdate();
        }
    }

    private String businessSnapshot() throws SQLException {
        try (var connection = owned.admin(); var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT json_build_array("
                    + "(SELECT COALESCE(json_agg(p ORDER BY post_id),'[]'::json) FROM pawbridge_community.posts p),"
                    + "(SELECT COALESCE(json_agg(e ORDER BY outbox_id),'[]'::json) FROM pawbridge_community.outbox_events e),"
                    + "(SELECT COALESCE(json_agg(d ORDER BY post_id),'[]'::json) FROM pawbridge_community.post_search_documents d))::text")) {
            rows.next(); return rows.getString(1);
        }
    }

    private void returned() {
        assertThat(pool.getMaximumPoolSize()).isOne();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TransactionSynchronizationManager.hasResource(pool)).isFalse();
        assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
    }
}
