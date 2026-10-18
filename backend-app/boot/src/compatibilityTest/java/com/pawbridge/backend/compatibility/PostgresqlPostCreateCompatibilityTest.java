package com.pawbridge.backend.compatibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pawbridge.backend.community.internal.post.application.CreatePost;
import com.pawbridge.backend.community.internal.post.application.PostSnapshot;
import com.pawbridge.backend.community.internal.post.domain.PostBoardType;
import com.pawbridge.backend.community.internal.post.domain.PostToCreate;
import com.pawbridge.backend.community.internal.post.infrastructure.JdbcPostReader;
import com.pawbridge.backend.community.internal.post.infrastructure.JdbcPostWriter;
import com.pawbridge.backend.user.internal.nickname.infrastructure.JdbcUserNicknameQuery;
import com.pawbridge.communityservice.domain.entity.BoardType;
import com.pawbridge.communityservice.domain.entity.OutboxEvent;
import com.pawbridge.communityservice.domain.entity.Post;
import com.pawbridge.communityservice.domain.entity.StringListConverter;
import com.pawbridge.communityservice.dto.response.PostResponse;
import com.pawbridge.communityservice.search.KoreanSearchTerms;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual JDBC writes versus hash-pinned Hibernate entity writes, in each JVM zone. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class PostgresqlPostCreateCompatibilityTest {
    private final ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00.123456Z"), ZoneId.systemDefault());
    private F3PostgresqlFixture fixture;
    private HikariDataSource reader;
    private HikariDataSource writer;
    private JdbcTemplate readJdbc;
    private JdbcTemplate writeJdbc;
    private JdbcPostWriter posts;
    private JdbcPostReader reads;
    private JdbcUserNicknameQuery nicknames;
    private SessionFactory legacy;

    @BeforeAll
    void openGuardedSameDatabaseWithIndependentPoolsAndTransactions() throws Exception {
        fixture = F3PostgresqlFixture.open();
        reader = pool(fixture.reads().applicationUser(), fixture.reads().applicationPassword(), 2, "f3-reader");
        writer = pool(F3PostgresqlFixture.WRITER, fixture.writerPassword(), 1, "f3-writer");
        readJdbc = new JdbcTemplate(reader);
        writeJdbc = new JdbcTemplate(writer);
        DataSourceTransactionManager readManager = new DataSourceTransactionManager(reader);
        readManager.setEnforceReadOnly(true);
        TransactionTemplate readTx = new TransactionTemplate(readManager);
        readTx.setReadOnly(true);
        readTx.setTimeout(5);
        TransactionTemplate writeTx = new TransactionTemplate(new DataSourceTransactionManager(writer));
        writeTx.setTimeout(5);
        posts = new JdbcPostWriter(writeJdbc, writeTx, mapper, clock);
        reads = new JdbcPostReader(readJdbc, readTx, mapper);
        nicknames = new JdbcUserNicknameQuery(readJdbc, readTx);
        Configuration config = new Configuration().addAnnotatedClass(Post.class).addAnnotatedClass(OutboxEvent.class)
                .addAttributeConverter(StringListConverter.class)
                .setProperty(AvailableSettings.DEFAULT_SCHEMA, "pawbridge_community")
                .setProperty(AvailableSettings.HBM2DDL_AUTO, "none")
                .setProperty(AvailableSettings.JDBC_TIME_ZONE, "UTC")
                .setProperty(AvailableSettings.SHOW_SQL, "false");
        config.getProperties().put(AvailableSettings.JAKARTA_NON_JTA_DATASOURCE, writer);
        legacy = config.buildSessionFactory();
        assertThat(reader.getMaximumPoolSize()).isEqualTo(2);
        assertThat(writer.getMaximumPoolSize()).isOne();
        assertThat(reader.getMinimumIdle()).isZero();
        assertThat(writer.getMinimumIdle()).isZero();
    }

    @AfterEach void deleteOnlyThisFixtureAuthorsRows() throws Exception {
        if (fixture != null) fixture.close();
        returned();
    }

    @AfterAll void closeNoriHibernateAndBothPools() throws Exception {
        try {
            if (posts != null) posts.close();
            if (legacy != null) legacy.close();
        } finally {
            if (reader != null) reader.close();
            if (writer != null) writer.close();
            if (fixture != null) fixture.close();
        }
    }

    @Test
    void givenFixedClockAndLegacyJpaWrite__whenCandidateWrites__thenEachWriteAndReadPathHasLegacyDateAndJsonMeaning()
            throws Exception {
        LocalDateTime now = LocalDateTime.now(clock);
        List<String> images = List.of("https://fixture.invalid/강아지.jpg", "https://fixture.invalid/영상.mp4");
        Post legacyPost = Post.builder().authorId(F3PostgresqlFixture.AUTHOR).title("한글 강아지 입양")
                .content("보호소 반려동물 이야기").boardType(BoardType.ADOPTION).imageUrls(images)
                .createdAt(now).updatedAt(now).build();
        try (var session = legacy.openSession()) {
            var tx = session.beginTransaction();
            session.persist(legacyPost);
            tx.commit();
        }
        PostSnapshot candidate = posts.create(new PostToCreate(F3PostgresqlFixture.AUTHOR,
                legacyPost.getTitle(), legacyPost.getContent(), PostBoardType.ADOPTION, images));
        JsonNode legacyWrite = mapper.valueToTree(PostResponse.fromEntity(legacyPost, "합성닉네임"));
        JsonNode candidateWrite = mapper.valueToTree(candidate);
        for (String field : List.of("authorId", "title", "content", "boardType", "imageUrls", "createdAt", "updatedAt")) {
            assertThat(candidateWrite.get(field)).as("write path %s / %s", field, ZoneId.systemDefault())
                    .isEqualTo(legacyWrite.get(field));
        }
        Post legacyReloaded;
        Post candidateReloaded;
        try (var session = legacy.openSession()) {
            legacyReloaded = session.find(Post.class, legacyPost.getPostId());
            candidateReloaded = session.find(Post.class, candidate.postId());
        }
        assertThat(candidateReloaded.getCreatedAt()).isEqualTo(legacyReloaded.getCreatedAt());
        assertThat(candidateReloaded.getUpdatedAt()).isEqualTo(legacyReloaded.getUpdatedAt());
        assertThat(candidateReloaded.getImageUrls()).isEqualTo(legacyReloaded.getImageUrls());
        PostSnapshot read = reads.findById(candidate.postId()).orElseThrow();
        assertThat(read.createdAt()).isEqualTo(candidateReloaded.getCreatedAt());
        assertThat(read.updatedAt()).isEqualTo(candidateReloaded.getUpdatedAt());
        assertThat(read.imageUrls()).isEqualTo(candidateReloaded.getImageUrls());
        assertThat(read.createdAt()).isEqualTo(now.withNano(123_456_000));
        returned();
    }

    @Test
    void givenThreeTableWrite__whenCommitting__thenExactEventAndLegacyNoriWeightsAreCommittedOnWriterConnection()
            throws Exception {
        PostSnapshot saved = posts.create(input("한글 강아지 입양", "보호소 반려동물 이야기"));
        assertThat(fixture.count("posts")).isOne();
        assertThat(fixture.count("outbox_events")).isOne();
        assertThat(fixture.count("post_search_documents")).isOne();
        Map<String,Object> event = writeJdbc.queryForMap("SELECT event_id,aggregate_type,aggregate_id,type,payload::text FROM pawbridge_community.outbox_events WHERE aggregate_id=?", String.valueOf(saved.postId()));
        assertThat(UUID.fromString((String) event.get("event_id")).toString()).isEqualTo(event.get("event_id"));
        assertThat(event).containsEntry("aggregate_type", "Post").containsEntry("aggregate_id", String.valueOf(saved.postId()))
                .containsEntry("type", "POST_CREATED");
        JsonNode expected = mapper.readTree(mapper.writeValueAsString(Map.of("postId",saved.postId(),"authorId",saved.authorId(),
                "title",saved.title(),"content",saved.content(),"boardType",saved.boardType(),"imageUrls",saved.imageUrls())));
        assertThat(mapper.readTree((String)event.get("payload"))).isEqualTo(expected);
        // Persist the exact legacy @JdbcTypeCode(JSON) OutboxEvent and compare its stored JSON shape.
        OutboxEvent oracle = OutboxEvent.builder().eventId(UUID.randomUUID().toString()).aggregateType("Post")
                .aggregateId(String.valueOf(saved.postId())).type("POST_CREATED").createdAt(LocalDateTime.now(clock))
                .payload(mapper.writeValueAsString(expected)).build();
        try (var session = legacy.openSession()) {
            var tx = session.beginTransaction(); session.persist(oracle); tx.commit();
        }
        assertThat(mapper.readTree(writeJdbc.queryForObject("SELECT payload::text FROM pawbridge_community.outbox_events WHERE event_id=?", String.class, oracle.getEventId())))
                .isEqualTo(expected);
        KoreanSearchTerms oracleTerms = new KoreanSearchTerms();
        try {
            Map<String,Object> document = writeJdbc.queryForMap("SELECT title_hash,content_hash,analyzer_version,tokens::text FROM pawbridge_community.post_search_documents WHERE post_id=?",saved.postId());
            String expectedTokens = writeJdbc.queryForObject("SELECT (setweight(to_tsvector('simple',?),'A') || setweight(to_tsvector('simple',?),'B'))::text", String.class, oracleTerms.document(saved.title()), oracleTerms.document(saved.content()));
            assertThat(document.get("tokens")).isEqualTo(expectedTokens);
            assertThat(document.get("analyzer_version")).isEqualTo(KoreanSearchTerms.VERSION);
            assertThat(document.get("title_hash")).isEqualTo(writeJdbc.queryForObject("SELECT md5(?)",String.class,saved.title()));
            assertThat(document.get("content_hash")).isEqualTo(writeJdbc.queryForObject("SELECT md5(?)",String.class,saved.content()));
        } finally { oracleTerms.close(); }
        returned();
    }

    @ParameterizedTest
    @ValueSource(strings={"outbox_events", "post_search_documents"})
    void givenLaterTablePermissionFailure__whenWriterFails__thenAllThreeBusinessTablesRollbackAndNextWriteRecovers(String table)
            throws Exception {
        fixture.execute("REVOKE INSERT ON pawbridge_community." + table + " FROM " + F3PostgresqlFixture.WRITER);
        try {
            assertThatThrownBy(() -> posts.create(input("롤백", "원자성"))).isInstanceOf(DataAccessException.class);
            assertThat(fixture.count("posts")).isZero();
            assertThat(fixture.count("outbox_events")).isZero();
            assertThat(fixture.count("post_search_documents")).isZero();
            returned();
        } finally {
            fixture.execute("GRANT INSERT ON pawbridge_community." + table + " TO " + F3PostgresqlFixture.WRITER);
        }
        posts.create(input("다음 성공", "시퀀스 간격을 원자성과 혼동하지 않음"));
        assertThat(fixture.count("posts")).isOne();
        assertThat(fixture.count("outbox_events")).isOne();
        assertThat(fixture.count("post_search_documents")).isOne();
    }

    @Test
    void givenIndependentReadAndWritePools__whenCreating__thenUploadAndNicknameFinishBeforeWriterTransaction()
            throws Exception {
        fixture.reads().insertUser(F3PostgresqlFixture.AUTHOR, "독립닉네임");
        CreatePost create = new CreatePost(files -> {
            returned(); return List.of();
        }, author -> {
            returned();
            String nickname = nicknames.findNickname(author).orElse(null);
            returned();
            return java.util.Optional.ofNullable(nickname);
        }, post -> {
            returned(); return posts.create(post);
        });
        assertThat(create.create(F3PostgresqlFixture.AUTHOR,"제목","내용",PostBoardType.COMMUNICATION,List.of()).authorNickname())
                .isEqualTo("독립닉네임");
        returned();
    }

    @Test
    void givenRestrictedWriterAndSelectOnlyReader__whenAccessingOtherResponsibilities__thenDatabaseDeniesThem()
            throws Exception {
        assertPermissionDenied(() -> writeJdbc.queryForList("SELECT * FROM pawbridge_user.users"));
        assertPermissionDenied(() -> writeJdbc.execute("CREATE TABLE pawbridge_community.f3_forbidden(id BIGINT)"));
        assertPermissionDenied(() -> writeJdbc.update("DELETE FROM pawbridge_community.posts"));
        assertPermissionDenied(() -> writeJdbc.update("UPDATE pawbridge_community.posts SET title=title"));
        assertPermissionDenied(() -> readJdbc.update("INSERT INTO pawbridge_community.posts(author_id,title,content,board_type,image_urls,created_at,updated_at) VALUES (1,'x','x','ADOPTION','[]',now(),now())"));
        assertPermissionDenied(() -> readJdbc.update("INSERT INTO pawbridge_community.outbox_events(aggregate_id,aggregate_type,created_at,event_id,payload,type) VALUES('x','Post',now(),'x','{}','POST_CREATED')"));
        try (Connection connection = writer.getConnection(); var statement = connection.createStatement();
             var role = statement.executeQuery("SELECT rolsuper,rolcreatedb,rolcreaterole,rolreplication,rolbypassrls FROM pg_roles WHERE rolname=current_user")) {
            assertThat(role.next()).isTrue();
            for (int column=1;column<=5;column++) assertThat(role.getBoolean(column)).isFalse();
        }
        returned();
    }

    private PostToCreate input(String title,String content) {
        return new PostToCreate(F3PostgresqlFixture.AUTHOR,title,content,PostBoardType.COMMUNICATION,List.of());
    }

    private HikariDataSource pool(String user,String password,int maximum,String name) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(fixture.reads().jdbcUrl()); config.setUsername(user); config.setPassword(password);
        config.setMaximumPoolSize(maximum); config.setMinimumIdle(0); config.setConnectionTimeout(2000);
        config.setValidationTimeout(1000); config.setPoolName(name);
        config.setConnectionInitSql("SET search_path TO public");
        return new HikariDataSource(config);
    }

    private void returned() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TransactionSynchronizationManager.hasResource(reader)).isFalse();
        assertThat(TransactionSynchronizationManager.hasResource(writer)).isFalse();
        if (reader != null) assertThat(reader.getHikariPoolMXBean().getActiveConnections()).isZero();
        if (writer != null) assertThat(writer.getHikariPoolMXBean().getActiveConnections()).isZero();
    }

    private static void assertPermissionDenied(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation).isInstanceOf(DataAccessException.class).satisfies(failure -> {
            Throwable cause=failure;
            while (cause != null && !(cause instanceof SQLException)) cause=cause.getCause();
            assertThat(cause).isInstanceOf(SQLException.class);
            assertThat(((SQLException)cause).getSQLState()).isEqualTo("42501");
        });
    }
}
