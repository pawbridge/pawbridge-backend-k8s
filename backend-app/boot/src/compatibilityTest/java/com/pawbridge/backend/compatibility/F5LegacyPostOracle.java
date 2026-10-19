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
    private String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception failure){throw new IllegalStateException(failure);}}
    @Override public void close(){context.close();}
    @Configuration(proxyBeanMethods=false)
    @EntityScan(basePackageClasses=Post.class)
    @EnableJpaAuditing(dateTimeProviderRef="legacyUpdateAuditTime")
    @ImportAutoConfiguration(HibernateJpaAutoConfiguration.class)
    static class LegacyJpa { }
}
