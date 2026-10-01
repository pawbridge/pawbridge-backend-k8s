package com.pawbridge.userservice.shelter;

import java.time.LocalDateTime;
import java.util.TimeZone;
import org.hibernate.cfg.Configuration;
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.assertThat;

/** Executes only against the named disposable database; never use a production URL. */
@EnabledIfEnvironmentVariable(named = "PAWBRIDGE_STATS_TEST_JDBC_URL",
        matches = "jdbc:postgresql://(127\\.0\\.0\\.1|stats-db)(:[0-9]+)?/pawbridge_admin_stats_test")
class ShelterTimestampPostgresTest {
    @Test void givenSeoulApplicationTimestamp__whenPersistedWithUtcJdbc__thenConfirmStoredWallClock() {
        TimeZone previous = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
        try (var factory = new Configuration().addAnnotatedClass(ShelterApplication.class)
                .setPhysicalNamingStrategy(new CamelCaseToUnderscoresNamingStrategy())
                .setProperty("hibernate.connection.url", System.getenv("PAWBRIDGE_STATS_TEST_JDBC_URL"))
                .setProperty("hibernate.connection.username", "postgres")
                .setProperty("hibernate.connection.password", "")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                .setProperty("hibernate.jdbc.time_zone", "UTC")
                .buildSessionFactory(); var session = factory.openSession()) {
            var tx = session.beginTransaction();
            var application = ShelterApplication.request(42L, "검증 보호소");
            var requested = LocalDateTime.of(2026, 10, 2, 0, 1);
            ReflectionTestUtils.setField(application, "requestedAt", requested);
            session.persist(application);
            session.flush();
            var stored = session.doReturningWork(connection -> {
                try (var statement = connection.prepareStatement("select requested_at from shelter_applications where id=?")) {
                    statement.setLong(1, application.getId());
                    try (var result = statement.executeQuery()) {
                        result.next();
                        return result.getObject(1, LocalDateTime.class);
                    }
                }
            });
            assertThat(stored).isEqualTo(requested.minusHours(9));
            session.clear();
            assertThat(session.find(ShelterApplication.class, application.getId()).getRequestedAt()).isEqualTo(requested);

            var date = LocalDate.of(2026, 10, 2);
            var previousRequest = ShelterApplication.request(43L, "이전 접수");
            ReflectionTestUtils.setField(previousRequest, "requestedAt", date.minusDays(10).atStartOfDay());
            previousRequest.approve(1L, "123", "확인");
            ReflectionTestUtils.setField(previousRequest, "reviewedAt", date.atStartOfDay());
            session.persist(previousRequest);
            var rejected = ShelterApplication.request(44L, "반려 접수");
            ReflectionTestUtils.setField(rejected, "requestedAt", date.atTime(23, 59));
            rejected.reject(1L, "확인 불가");
            ReflectionTestUtils.setField(rejected, "reviewedAt", date.atTime(23, 59));
            session.persist(rejected);
            var tomorrow = ShelterApplication.request(45L, "다음날 접수");
            ReflectionTestUtils.setField(tomorrow, "requestedAt", date.plusDays(1).atStartOfDay());
            tomorrow.reject(1L, "다음날 처리");
            ReflectionTestUtils.setField(tomorrow, "reviewedAt", date.plusDays(1).atStartOfDay());
            session.persist(tomorrow);
            session.flush();
            var repository = new JpaRepositoryFactory(session).getRepository(ShelterApplicationRepository.class);
            assertThat(repository.countDailyRequests(date.atStartOfDay(), date.plusDays(1).atStartOfDay()))
                    .containsExactly(new DailyShelterApplicationStats(date, 2));
            assertThat(repository.countReviews(date.atStartOfDay(), date.plusDays(1).atStartOfDay()))
                    .containsExactlyInAnyOrder(new ShelterReviewStats(ShelterApplicationStatus.APPROVED, 1),
                            new ShelterReviewStats(ShelterApplicationStatus.REJECTED, 1));
            assertThat(repository.countByStatus(ShelterApplicationStatus.PENDING)).isEqualTo(1);
            tx.rollback();
        } finally {
            TimeZone.setDefault(previous);
        }
    }
}
