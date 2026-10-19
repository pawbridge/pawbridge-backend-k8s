package com.pawbridge.backend.compatibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.community.internal.post.domain.PostBoardType;
import com.pawbridge.backend.community.internal.post.domain.PostToCreate;
import com.pawbridge.backend.community.internal.post.infrastructure.JdbcPostWriter;
import com.pawbridge.communityservice.domain.entity.Post;
import com.pawbridge.communityservice.domain.entity.OutboxEvent;
import com.pawbridge.communityservice.dto.response.PostResponse;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.PlatformTransactionManager;
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
    private AnnotationConfigApplicationContext context;
    private EntityManager entityManager;
    private TransactionTemplate transaction;
    private SimpleJpaRepository<Post,Long> posts;
    private SimpleJpaRepository<OutboxEvent,Long> events;
    private final ObjectMapper mapper=new ObjectMapper();

    @BeforeAll void openOnlyHostedSyntheticFixtureAndRealJpaAuditing() throws Exception {
        fixture=F3PostgresqlFixture.open();
        var config=new HikariConfig();config.setJdbcUrl(fixture.reads().jdbcUrl());
        config.setUsername(F3PostgresqlFixture.WRITER);config.setPassword(fixture.writerPassword());
        config.setMaximumPoolSize(1);config.setMinimumIdle(0);config.setConnectionTimeout(2000);
        writer=new HikariDataSource(config);jdbc=new JdbcTemplate(writer);
        creates=new JdbcPostWriter(jdbc,new TransactionTemplate(new DataSourceTransactionManager(writer)),mapper,CLOCK);
        var admin=new DriverManagerDataSource(fixture.reads().jdbcUrl(),
                F3PostgresqlFixture.required("F2_TEST_ADMIN_USER"),F3PostgresqlFixture.required("F2_TEST_ADMIN_PASSWORD"));
        context=new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("legacy-update-oracle",Map.of(
                "spring.jpa.hibernate.ddl-auto","none","spring.jpa.open-in-view","false",
                "spring.jpa.properties.hibernate.default_schema","pawbridge_community",
                "spring.jpa.properties.hibernate.jdbc.time_zone","UTC")));
        context.registerBean("dataSource",DataSource.class,()->admin);context.register(LegacyJpa.class);context.refresh();
        entityManager=SharedEntityManagerCreator.createSharedEntityManager(context.getBean(EntityManagerFactory.class));
        transaction=new TransactionTemplate(context.getBean(PlatformTransactionManager.class));transaction.setTimeout(10);
        posts=new SimpleJpaRepository<>(Post.class,entityManager);events=new SimpleJpaRepository<>(OutboxEvent.class,entityManager);
    }
    @AfterEach void preserveOnlyUnrelatedRowsAndReturnConnections() throws Exception {
        if(fixture!=null)fixture.close();
        if(writer!=null)assertThat(writer.getHikariPoolMXBean().getActiveConnections()).isZero();
    }
    @AfterAll void closeOracleAndSyntheticPool() throws Exception {
        try { if(context!=null)context.close();if(creates!=null)creates.close(); }
        finally { if(writer!=null)writer.close();if(fixture!=null)fixture.close(); }
    }

    @ParameterizedTest @ValueSource(strings={"omitted","same-title","same-images","changed-title","empty-title","changed-images"})
    void given__legacy_managed_post__when__saving_before_identity_outbox_and_response__then__observe_real_dirty_flush_and_auditing(String mode) throws Exception {
        var created=creates.create(new PostToCreate(F3PostgresqlFixture.AUTHOR,"기존 제목","기존 본문",PostBoardType.COMMUNICATION,List.of("https://fixture.invalid/f5/old.jpg")));
        var observation=transaction.execute(status->{
            Post post=entityManager.find(Post.class,created.postId());
            LocalDateTime before=post.getUpdatedAt();
            String title=mode.equals("same-title")?post.getTitle():mode.equals("changed-title")?"수정 제목":mode.equals("empty-title")?"":null;
            List<String> images=mode.equals("same-images")?List.copyOf(post.getImageUrls()):mode.equals("changed-images")?List.of():null;
            post.update(title,null,images);Post saved=posts.save(post);
            Map<String,Object> payload=Map.of("postId",saved.getPostId(),"authorId",saved.getAuthorId(),"title",saved.getTitle(),
                    "content",saved.getContent(),"boardType",saved.getBoardType().name(),"imageUrls",saved.getImageUrls());
            events.save(OutboxEvent.builder().eventId(UUID.randomUUID().toString()).aggregateType("Post")
                    .aggregateId(saved.getPostId().toString()).type("POST_UPDATED").payload(json(payload)).createdAt(LocalDateTime.now(CLOCK)).build());
            PostResponse response=PostResponse.fromEntity(saved,"작성자");
            entityManager.flush();
            return new Observation(before,response.updatedAt(),saved.getUpdatedAt());
        });
        boolean changed=List.of("changed-title","empty-title","changed-images").contains(mode);
        LocalDateTime committed=transaction.execute(status->entityManager.find(Post.class,created.postId()).getUpdatedAt());
        assertThat(observation.afterFlush()).isEqualTo(changed?AUDIT.get():observation.before());
        assertThat(committed).isEqualTo(observation.afterFlush());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pawbridge_community.outbox_events WHERE aggregate_id=? AND type='POST_UPDATED'",Integer.class,Long.toString(created.postId()))).isOne();
        System.out.println("F5_LEGACY_UPDATE mode="+mode+" responseMatchesBefore="+observation.response().equals(observation.before())
                +" responseMatchesCommit="+observation.response().equals(committed)+" audited="+changed+" zone="+ZoneId.systemDefault());
    }

    @ParameterizedTest @ValueSource(strings={"sql-null","json-null","empty-array"})
    void given__legacy_json_storage__when__reading_and_constructing_update_payload__then__observe_converter_null_contract(String mode) throws Exception {
        var created=creates.create(new PostToCreate(F3PostgresqlFixture.AUTHOR,"기존 제목","기존 본문",PostBoardType.COMMUNICATION,List.of()));
        String expression=mode.equals("sql-null")?"NULL":mode.equals("json-null")?"'null'::json":"'[]'::json";
        fixture.execute("UPDATE pawbridge_community.posts SET image_urls="+expression+" WHERE post_id="+created.postId());
        boolean mappedNull=transaction.execute(status->entityManager.find(Post.class,created.postId()).getImageUrls()==null);
        if(mappedNull){
            assertThatThrownBy(()->transaction.execute(status->{
                Post post=entityManager.find(Post.class,created.postId());post.update("수정 제목",null,null);
                Map.of("imageUrls",posts.save(post).getImageUrls());return null;
            })).isInstanceOf(NullPointerException.class);
            assertThat(jdbc.queryForObject("SELECT title FROM pawbridge_community.posts WHERE post_id=?",String.class,created.postId())).isEqualTo("기존 제목");
        } else transaction.executeWithoutResult(status->assertThat(entityManager.find(Post.class,created.postId()).getImageUrls()).isEmpty());
        System.out.println("F5_LEGACY_NULL mode="+mode+" mappedNull="+mappedNull);
    }
    private String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception failure){throw new IllegalStateException(failure);}}
    private record Observation(LocalDateTime before,LocalDateTime response,LocalDateTime afterFlush){}
    @Configuration(proxyBeanMethods=false)
    @EntityScan(basePackageClasses=Post.class)
    @EnableJpaAuditing(dateTimeProviderRef="legacyUpdateAuditTime")
    @ImportAutoConfiguration(HibernateJpaAutoConfiguration.class)
    static class LegacyJpa {
        @Bean DateTimeProvider legacyUpdateAuditTime(){return ()->Optional.of(AUDIT.get());}
    }
}
