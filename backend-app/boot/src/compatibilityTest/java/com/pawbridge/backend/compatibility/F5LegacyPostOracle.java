package com.pawbridge.backend.compatibility;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.communityservice.domain.entity.Post;
import com.pawbridge.communityservice.domain.entity.OutboxEvent;
import com.pawbridge.communityservice.dto.response.PostResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual pinned legacy mappings/auditing, managed save, identity Outbox and response-before-commit order. */
final class F5LegacyPostOracle implements AutoCloseable {
    private final AnnotationConfigApplicationContext context=new AnnotationConfigApplicationContext();
    private final EntityManager entityManager;
    private final TransactionTemplate transaction;
    private final SimpleJpaRepository<Post,Long> posts;
    private final SimpleJpaRepository<OutboxEvent,Long> events;
    private final ObjectMapper mapper=new ObjectMapper();
    private final Clock clock;
    private final java.util.List<String> pageOperations = new java.util.ArrayList<>();
    private boolean pageActive;
    private Runnable beforePageCount = () -> { };
    record PageTransaction(boolean readOnly, int isolation, String role) { }
    private PageTransaction pageTransaction;

    F5LegacyPostOracle(F3PostgresqlFixture fixture,Clock clock) {
        this.clock=clock;
        var admin=new DriverManagerDataSource(fixture.reads().jdbcUrl(),
                F3PostgresqlFixture.required("F2_TEST_ADMIN_USER"),F3PostgresqlFixture.required("F2_TEST_ADMIN_PASSWORD"));
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("legacy-update-oracle",Map.of(
                "spring.jpa.hibernate.ddl-auto","none","spring.jpa.open-in-view","false",
                "spring.jpa.properties.hibernate.default_schema","pawbridge_community",
                "spring.jpa.properties.hibernate.jdbc.time_zone","UTC")));
        context.registerBean("dataSource",DataSource.class,()->admin);
        context.registerBean("legacyUpdateAuditTime",DateTimeProvider.class,()->()->Optional.of(LocalDateTime.now(clock)));
        context.registerBean("adminPageSqlKindsOnly", org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer.class,
                () -> properties -> properties.put("hibernate.session_factory.statement_inspector",
                        (org.hibernate.resource.jdbc.spi.StatementInspector) sql -> {
                            if (pageActive && sql.contains("pawbridge_community.posts")) {
                                boolean count = sql.toLowerCase(java.util.Locale.ROOT).contains("count(");
                                pageOperations.add(count ? "count" : "content");
                                if (count) beforePageCount.run();
                            }
                            return sql;
                        }));
        context.register(LegacyJpa.class);context.refresh();
        entityManager=SharedEntityManagerCreator.createSharedEntityManager(context.getBean(EntityManagerFactory.class));
        transaction=new TransactionTemplate(context.getBean(PlatformTransactionManager.class));transaction.setTimeout(10);
        posts=new SimpleJpaRepository<>(Post.class,entityManager);events=new SimpleJpaRepository<>(OutboxEvent.class,entityManager);
    }

    PostResponse update(long id,String title,String content,List<String> images) {
        return transaction.execute(status->{
            Post post=entityManager.find(Post.class,id);post.update(title,content,images);Post saved=posts.save(post);
            Map<String,Object> payload=Map.of("postId",saved.getPostId(),"authorId",saved.getAuthorId(),"title",saved.getTitle(),
                    "content",saved.getContent(),"boardType",saved.getBoardType().name(),"imageUrls",saved.getImageUrls());
            events.save(OutboxEvent.builder().eventId(UUID.randomUUID().toString()).aggregateType("Post")
                    .aggregateId(saved.getPostId().toString()).type("POST_UPDATED").payload(json(payload)).createdAt(LocalDateTime.now(clock)).build());
            return PostResponse.fromEntity(saved,"작성자");
        });
    }
    PostResponse read(long id) {
        return transaction.execute(status->PostResponse.fromEntity(entityManager.find(Post.class,id),"작성자"));
    }
    /** Actual raw F6 Repository/Pageable and source DTO mapping, not the whole PostService. */
    org.springframework.data.domain.Page<PostResponse> page(org.springframework.data.domain.Pageable pageable, String nickname) {
        var readOnly = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        readOnly.setReadOnly(true);
        readOnly.setTimeout(5); // Bounded test wiring, not a claim about the original service timeout.
        pageOperations.clear(); pageActive = true;
        try {
            return readOnly.execute(status -> {
                entityManager.unwrap(org.hibernate.Session.class).doWork(connection -> {
                    try (var statement = connection.createStatement()) {
                        statement.execute("SET LOCAL ROLE pawbridge_f2_reader");
                        try (var result = statement.executeQuery("SELECT current_user")) {
                            if (!result.next()) throw new IllegalStateException("Missing original page role");
                            pageTransaction = new PageTransaction(connection.isReadOnly(), connection.getTransactionIsolation(), result.getString(1));
                        }
                    }
                });
                var repository = new org.springframework.data.jpa.repository.support.JpaRepositoryFactory(entityManager)
                        .getRepository(com.pawbridge.communityservice.domain.repository.PostRepository.class);
                return repository.findByDeletedAtIsNull(pageable).map(post -> PostResponse.fromEntity(post, nickname));
            });
        } finally { pageActive = false; beforePageCount = () -> { }; }
    }
    void beforePageCount(Runnable callback) { beforePageCount = java.util.Objects.requireNonNull(callback); }
    java.util.List<String> pageOperations() { return java.util.List.copyOf(pageOperations); }
    PageTransaction pageTransaction() { return pageTransaction; }
    boolean pageResourceBound() {
        return org.springframework.transaction.support.TransactionSynchronizationManager.hasResource(context.getBean(DataSource.class));
    }
    private String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception failure){throw new IllegalStateException(failure);}}
    @Override public void close(){context.close();}
    @Configuration(proxyBeanMethods=false)
    @EntityScan(basePackageClasses=Post.class)
    @EnableJpaAuditing(dateTimeProviderRef="legacyUpdateAuditTime")
    @ImportAutoConfiguration(HibernateJpaAutoConfiguration.class)
    static class LegacyJpa { }
}
