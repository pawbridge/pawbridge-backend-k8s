package com.pawbridge.backend.compatibility;

import com.pawbridge.communityservice.domain.entity.Comment;
import com.pawbridge.communityservice.domain.repository.CommentRepository;
import com.pawbridge.communityservice.dto.response.CommentResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real pinned mappings and auditing callbacks; the DTO is captured before transaction completion. */
final class F11LegacyCommentOracle implements AutoCloseable {
    private final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    private final EntityManager entityManager;
    private final CommentRepository repository;
    private final TransactionTemplate transaction;
    private final Runnable verifyOwnership;

    F11LegacyCommentOracle(F3PostgresqlFixture fixture, Clock clock, Runnable verifyOwnership) {
        this.verifyOwnership = verifyOwnership;
        verifyOwnership.run();
        var admin = new DriverManagerDataSource(fixture.reads().jdbcUrl(),
                F3PostgresqlFixture.required("F2_TEST_ADMIN_USER"), F3PostgresqlFixture.required("F2_TEST_ADMIN_PASSWORD"));
        try {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("legacy-comment-update-oracle", Map.of(
                    "spring.jpa.hibernate.ddl-auto", "none", "spring.jpa.open-in-view", "false",
                    "spring.jpa.properties.hibernate.default_schema", "pawbridge_community",
                    "spring.jpa.properties.hibernate.jdbc.time_zone", "UTC")));
            context.registerBean("dataSource", DataSource.class, () -> admin);
            context.registerBean("legacyCommentUpdateAuditTime", DateTimeProvider.class,
                    () -> () -> Optional.of(LocalDateTime.now(clock)));
            context.register(LegacyJpa.class); context.refresh();
            entityManager = SharedEntityManagerCreator.createSharedEntityManager(context.getBean(EntityManagerFactory.class));
            repository = new JpaRepositoryFactory(entityManager).getRepository(CommentRepository.class);
            transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            transaction.setTimeout(10);
        } catch (RuntimeException | Error failure) {
            context.close(); throw failure;
        }
    }

    CommentResponse update(long id, String content, Supplier<String> nickname) {
        verifyOwnership.run();
        return transaction.execute(status -> {
            Comment entity = repository.findByCommentIdAndDeletedAtIsNull(id).orElseThrow();
            entity.update(content);
            Comment saved = repository.save(entity);
            String authorNickname = nickname.get();
            // Do not markModified manually: that would dirty unchanged content and alter this snapshot.
            return CommentResponse.fromEntity(saved, authorNickname);
        });
    }

    @Override public void close() { context.close(); }

    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = Comment.class)
    @EnableJpaAuditing(dateTimeProviderRef = "legacyCommentUpdateAuditTime")
    @ImportAutoConfiguration(HibernateJpaAutoConfiguration.class)
    static class LegacyJpa { }
}
