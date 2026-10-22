package com.pawbridge.backend.compatibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pawbridge.backend.community.internal.post.domain.PostBoardType;
import com.pawbridge.backend.community.internal.post.domain.PostToCreate;
import com.pawbridge.backend.community.internal.post.infrastructure.JdbcPostWriter;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real legacy entities, Spring auditing and identity INSERT; observes the service's response-before-commit order. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LegacyPostUpdateObservationTest {
    private static final Clock CLOCK=Clock.fixed(Instant.parse("2026-01-01T00:00:00.123456Z"),ZoneId.systemDefault());
    private static final AtomicReference<LocalDateTime> AUDIT=new AtomicReference<>(LocalDateTime.now(CLOCK).plusHours(1));
    private F3PostgresqlFixture fixture;
    private HikariDataSource writer;
    private JdbcPostWriter creates;
    private JdbcTemplate jdbc;
    private F5LegacyPostOracle oracle;

    @BeforeAll void openOnlyHostedSyntheticFixtureAndRealJpaAuditing() throws Exception {
        fixture=F3PostgresqlFixture.open();
        var config=new HikariConfig();config.setJdbcUrl(fixture.reads().jdbcUrl());
        config.setUsername(F3PostgresqlFixture.WRITER);config.setPassword(fixture.writerPassword());
        config.setMaximumPoolSize(1);config.setMinimumIdle(0);config.setConnectionTimeout(2000);
        writer=new HikariDataSource(config);jdbc=new JdbcTemplate(writer);
        creates=new JdbcPostWriter(jdbc,new TransactionTemplate(new DataSourceTransactionManager(writer)),new com.fasterxml.jackson.databind.ObjectMapper(),CLOCK);
        oracle=new F5LegacyPostOracle(fixture,Clock.offset(CLOCK,java.time.Duration.ofHours(1)));
    }
    @AfterEach void preserveOnlyUnrelatedRowsAndReturnConnections() throws Exception {
        if(fixture!=null)fixture.close();
        if(writer!=null)assertThat(writer.getHikariPoolMXBean().getActiveConnections()).isZero();
    }
    @AfterAll void closeOracleAndSyntheticPool() throws Exception {
        try { if(oracle!=null)oracle.close();if(creates!=null)creates.close(); }
        finally { if(writer!=null)writer.close();if(fixture!=null)fixture.close(); }
    }

    @ParameterizedTest @ValueSource(strings={"omitted","same-title","same-images","changed-title","empty-title","changed-images"})
    void given__legacy_managed_post__when__saving_before_identity_outbox_and_response__then__observe_real_dirty_flush_and_auditing(String mode) throws Exception {
        var created=creates.create(new PostToCreate(F3PostgresqlFixture.AUTHOR,"기존 제목","기존 본문",PostBoardType.COMMUNICATION,List.of("https://fixture.invalid/f5/old.jpg")));
        var before=oracle.read(created.postId());
        String title=mode.equals("same-title")?before.title():mode.equals("changed-title")?"수정 제목":mode.equals("empty-title")?"":null;
        List<String> images=mode.equals("same-images")?List.copyOf(before.imageUrls()):mode.equals("changed-images")?List.of():null;
        var response=oracle.update(created.postId(),title,null,images);
        var committedPost=oracle.read(created.postId());
        var observation=new Observation(before.updatedAt(),response.updatedAt(),committedPost.updatedAt());
        boolean changed=List.of("changed-title","empty-title","changed-images").contains(mode);
        LocalDateTime committed=committedPost.updatedAt();
        assertThat(observation.response()).isEqualTo(observation.before());
        assertThat(observation.afterFlush()).isEqualTo(changed?AUDIT.get():observation.before());
        assertThat(committed).isEqualTo(observation.afterFlush());
        // The candidate writer no longer holds outbox privileges; observe the legacy oracle's row as the fixture admin.
        try (var connection=fixture.admin();
             var statement=connection.prepareStatement("SELECT count(*) FROM pawbridge_community.outbox_events WHERE aggregate_id=? AND type='POST_UPDATED'")) {
            statement.setString(1,Long.toString(created.postId()));
            try (var row=statement.executeQuery()) { row.next(); assertThat(row.getInt(1)).isOne(); }
        }
        System.out.println("F5_LEGACY_UPDATE mode="+mode+" responseMatchesBefore="+observation.response().equals(observation.before())
                +" responseMatchesCommit="+observation.response().equals(committed)+" audited="+changed+" zone="+ZoneId.systemDefault());
    }

    @ParameterizedTest @ValueSource(strings={"sql-null","json-null","empty-array"})
    void given__legacy_json_storage__when__reading_and_constructing_update_payload__then__observe_converter_null_contract(String mode) throws Exception {
        var created=creates.create(new PostToCreate(F3PostgresqlFixture.AUTHOR,"기존 제목","기존 본문",PostBoardType.COMMUNICATION,List.of()));
        String expression=mode.equals("sql-null")?"NULL":mode.equals("json-null")?"'null'::json":"'[]'::json";
        fixture.execute("UPDATE pawbridge_community.posts SET image_urls="+expression+" WHERE post_id="+created.postId());
        boolean mappedNull=oracle.read(created.postId()).imageUrls()==null;
        assertThat(mappedNull).isEqualTo(mode.equals("json-null"));
        if(mappedNull){
            assertThatThrownBy(()->oracle.update(created.postId(),"수정 제목",null,null)).isInstanceOf(NullPointerException.class);
            assertThat(jdbc.queryForObject("SELECT title FROM pawbridge_community.posts WHERE post_id=?",String.class,created.postId())).isEqualTo("기존 제목");
        } else assertThat(oracle.read(created.postId()).imageUrls()).isEmpty();
        System.out.println("F5_LEGACY_NULL mode="+mode+" mappedNull="+mappedNull);
    }
    private record Observation(LocalDateTime before,LocalDateTime response,LocalDateTime afterFlush){}
}
