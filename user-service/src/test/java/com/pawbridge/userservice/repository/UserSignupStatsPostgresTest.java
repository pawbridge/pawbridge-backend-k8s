package com.pawbridge.userservice.repository;

import com.pawbridge.userservice.entity.User;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.TimeZone;
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import static org.assertj.core.api.Assertions.assertThat;

/** Creates tables only in the explicitly named disposable test database. */
@EnabledIfEnvironmentVariable(named = "PAWBRIDGE_STATS_TEST_JDBC_URL",
        matches = "jdbc:postgresql://(127\\.0\\.0\\.1|stats-db)(:[0-9]+)?/pawbridge_admin_stats_test")
class UserSignupStatsPostgresTest {
    @ParameterizedTest
    @CsvSource({"UTC,2026-09-30", "Asia/Seoul,2026-09-30",
            "UTC,2026-12-31", "Asia/Seoul,2026-12-31",
            "UTC,2024-02-29", "Asia/Seoul,2024-02-29"})
    void givenUtcStoredSignups__whenGroupedByKoreanDate__thenKeepMidnightAndCalendarBoundaries(
            String jvmZone, LocalDate day) {
        TimeZone previous = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone(jvmZone));
        try (var factory = new Configuration().addAnnotatedClass(User.class)
                .setPhysicalNamingStrategy(new CamelCaseToUnderscoresNamingStrategy())
                .setProperty("hibernate.connection.url", System.getenv("PAWBRIDGE_STATS_TEST_JDBC_URL"))
                .setProperty("hibernate.connection.username", "postgres")
                .setProperty("hibernate.connection.password", "")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                .setProperty("hibernate.jdbc.time_zone", "UTC")
                .buildSessionFactory(); var session = factory.openSession()) {
            var tx = session.beginTransaction();
            LocalDateTime start = day.atStartOfDay().minusHours(9);
            LocalDateTime end = day.plusDays(1).atStartOfDay().minusHours(9);
            LocalDateTime[] times = {start.minusNanos(1000), start, end.minusNanos(1000), end, end.plusHours(1)};
            session.doWork(connection -> {
                try (var statement = connection.prepareStatement("INSERT INTO users "
                        + "(email,name,nickname,role,provider,created_at) VALUES (?, '검증 회원', ?, 'ROLE_USER', 'LOCAL', ?)")) {
                    for (int i = 0; i < times.length; i++) {
                        statement.setString(1, "stats-" + i + "@example.invalid");
                        statement.setString(2, "stats_" + i);
                        // PostgreSQL LocalDateTime binding inserts the raw UTC wall clock, independent of JVM zone.
                        statement.setObject(3, times[i]);
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
            });
            var repository = new JpaRepositoryFactory(session).getRepository(UserRepository.class);
            assertThat(repository.countDailySignups(day, day))
                    .extracting(row -> row.date() + ":" + row.count()).containsExactly(day + ":2");
            assertThat(repository.countDailySignups(day.minusDays(1), day.plusDays(1)))
                    .extracting(row -> row.date() + ":" + row.count())
                    .containsExactly(day.minusDays(1) + ":1", day + ":2", day.plusDays(1) + ":2");
            assertThat(repository.countDailySignups(day.minusDays(2), day.minusDays(2))).isEmpty();
            tx.rollback();
        } finally {
            TimeZone.setDefault(previous);
        }
    }
}
