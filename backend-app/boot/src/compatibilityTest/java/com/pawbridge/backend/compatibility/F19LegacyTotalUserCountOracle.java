package com.pawbridge.backend.compatibility;

import com.pawbridge.userservice.entity.User;
import jakarta.persistence.EntityManagerFactory;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Test-only inherited Spring Data count with the pinned original User entity.
 * The explicit read-only transaction is harness wiring, not the original UserService proxy.
 */
final class F19LegacyTotalUserCountOracle implements AutoCloseable {
    private final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    private final SimpleJpaRepository<User, Long> users;
    private final TransactionTemplate transaction;
    private final Runnable verifyOwnership;

    F19LegacyTotalUserCountOracle(F3PostgresqlFixture fixture, Runnable verifyOwnership) {
        this.verifyOwnership = verifyOwnership;
        try {
            verifyOwnership.run();
            var admin = new DriverManagerDataSource(fixture.reads().jdbcUrl(),
                    F3PostgresqlFixture.required("F2_TEST_ADMIN_USER"), F3PostgresqlFixture.required("F2_TEST_ADMIN_PASSWORD"));
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("legacy-total-user-count-oracle", Map.of(
                    "spring.jpa.hibernate.ddl-auto", "none", "spring.jpa.open-in-view", "false",
                    "spring.jpa.properties.hibernate.default_schema", "pawbridge_user",
                    "spring.jpa.properties.hibernate.jdbc.time_zone", "UTC")));
            context.registerBean("dataSource", DataSource.class, () -> admin);
            context.register(LegacyJpa.class);
            context.refresh();
            var entityManager = SharedEntityManagerCreator.createSharedEntityManager(context.getBean(EntityManagerFactory.class));
            // The pinned UserRepository does not override count. Do not initialize its unrelated custom queries.
            users = new SimpleJpaRepository<>(User.class, entityManager);
            transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            transaction.setReadOnly(true);
            transaction.setTimeout(3);
        } catch (RuntimeException | Error failure) {
            context.close();
            throw failure;
        }
    }

    long count() {
        verifyOwnership.run();
        return transaction.execute(status -> users.count());
    }

    @Override public void close() { context.close(); }

    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = User.class)
    @ImportAutoConfiguration(HibernateJpaAutoConfiguration.class)
    static class LegacyJpa { }
}
