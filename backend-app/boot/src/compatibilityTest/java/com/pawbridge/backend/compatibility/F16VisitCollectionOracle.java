package com.pawbridge.backend.compatibility;

import static org.assertj.core.api.Assertions.assertThat;

import com.pawbridge.communityservice.visits.VisitCookie;
import com.pawbridge.communityservice.visits.VisitRepository;
import com.pawbridge.communityservice.visits.VisitService;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/** Existing raw F16 classes; the actual F5 Hibernate/JpaTM context exposes the same JDBC datasource. */
final class F16VisitCollectionOracle implements AutoCloseable {
    static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    final DataSource dataSource;
    final JpaTransactionManager manager;
    final JdbcTemplate jdbc;
    final VisitService service;

    F16VisitCollectionOracle(F3PostgresqlFixture fixture, Clock clock, Runnable verifyOwnership,
                            Function<DataSource, JdbcTemplate> jdbcFactory) {
        verifyOwnership.run();
        dataSource = new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                verifyOwnership.run(); Connection connection = fixture.admin();
                try (var statement = connection.createStatement()) {
                    statement.execute("SET statement_timeout='8s'; SET lock_timeout='5s'; SET application_name='f16-visit-collection-original'");
                } catch (SQLException | RuntimeException failure) { connection.close(); throw failure; }
                return connection;
            }
            @Override public Connection getConnection(String user, String password) throws SQLException {
                throw new SQLException("Only the owned reference datasource is permitted");
            }
        };
        try {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("f16-collection-owned-original", Map.of(
                    "spring.jpa.hibernate.ddl-auto", "none", "spring.jpa.open-in-view", "false",
                    "spring.jpa.properties.hibernate.default_schema", "pawbridge_community",
                    "spring.jpa.properties.hibernate.jdbc.time_zone", "UTC")));
            context.registerBean("dataSource", DataSource.class, () -> dataSource);
            context.registerBean("legacyUpdateAuditTime", DateTimeProvider.class,
                    () -> () -> Optional.of(LocalDateTime.now(clock)));
            context.register(F5LegacyPostOracle.LegacyJpa.class); context.refresh();
            PlatformTransactionManager actual = context.getBean(PlatformTransactionManager.class);
            assertThat(actual).isInstanceOf(JpaTransactionManager.class); manager = (JpaTransactionManager) actual;
            assertThat(manager.getDataSource()).isSameAs(dataSource);
            jdbc = jdbcFactory.apply(dataSource);
            service = new VisitService(new VisitRepository(jdbc), new VisitCookie(KEY), clock, manager);
        } catch (RuntimeException | Error failure) { context.close(); throw failure; }
    }
    @Override public void close() { context.close(); }
}
