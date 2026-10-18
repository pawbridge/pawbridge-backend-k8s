package com.pawbridge.animalservice.admin.repository;

import com.pawbridge.animalservice.entity.Animal;
import com.pawbridge.animalservice.entity.Shelter;
import com.pawbridge.animalservice.enums.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.TimeZone;
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.assertThat;

/** Creates tables only in the explicitly named disposable test database. */
@EnabledIfEnvironmentVariable(named = "PAWBRIDGE_STATS_TEST_JDBC_URL",
        matches = "jdbc:postgresql://(127\\.0\\.0\\.1|stats-db)(:[0-9]+)?/pawbridge_admin_stats_test")
class AdminCollectionStatsPostgresTest {
    @ParameterizedTest
    @CsvSource({"UTC,2026-09-30", "Asia/Seoul,2026-09-30",
            "UTC,2026-12-31", "Asia/Seoul,2026-12-31",
            "UTC,2024-02-29", "Asia/Seoul,2024-02-29"})
    void givenUtcStoredAnimals__whenGroupedByKoreanDate__thenKeepCollectionBoundariesSeparateFromIntake(
            String jvmZone, LocalDate day) {
        TimeZone previous = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone(jvmZone));
        try (var factory = new Configuration().addAnnotatedClass(Animal.class).addAnnotatedClass(Shelter.class)
                .setPhysicalNamingStrategy(new CamelCaseToUnderscoresNamingStrategy())
                .setProperty("hibernate.connection.url", System.getenv("PAWBRIDGE_STATS_TEST_JDBC_URL"))
                .setProperty("hibernate.connection.username", "postgres")
                .setProperty("hibernate.connection.password", "")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                .setProperty("hibernate.jdbc.time_zone", "UTC")
                .buildSessionFactory(); var session = factory.openSession()) {
            var tx = session.beginTransaction();
            var shelter = Shelter.builder().careRegNo("stats-test-only").name("검증 보호소").build();
            ReflectionTestUtils.setField(shelter, "createdAt", day.atStartOfDay());
            session.persist(shelter);
            LocalDateTime start = day.atStartOfDay().minusHours(9);
            LocalDateTime end = day.plusDays(1).atStartOfDay().minusHours(9);
            LocalDateTime[] times = {start.minusNanos(1000), start, end.minusNanos(1000), end, end.plusHours(1)};
            for (int i = 0; i < times.length; i++) {
                var animal = Animal.builder().apmsNoticeNo("stats-" + i).species(Species.DOG)
                        .gender(Gender.UNKNOWN).neuterStatus(NeuterStatus.UNKNOWN).status(AnimalStatus.PROTECT)
                        .apiSource(ApiSource.APMS_ANIMAL).noticeStartDate(day).noticeEndDate(day.plusDays(10))
                        .happenDate(day.minusDays(10)).shelter(shelter).build();
                ReflectionTestUtils.setField(animal, "createdAt", day.atStartOfDay());
                session.persist(animal);
            }
            session.flush();
            session.doWork(connection -> {
                try (var statement = connection.prepareStatement("UPDATE animals SET created_at=? WHERE apms_notice_no=?")) {
                    for (int i = 0; i < times.length; i++) {
                        statement.setObject(1, times[i]);
                        statement.setString(2, "stats-" + i);
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
            });
            session.clear();
            var repository = new JpaRepositoryFactory(session).getRepository(AdminStatsRepository.class);
            assertThat(repository.countDailyAnimals(day, day))
                    .extracting(row -> row.getDate() + ":" + row.getCount()).containsExactly(day + ":2");
            assertThat(repository.countDailyAnimals(day.minusDays(1), day.plusDays(1)))
                    .extracting(row -> row.getDate() + ":" + row.getCount())
                    .containsExactly(day.minusDays(1) + ":1", day + ":2", day.plusDays(1) + ":2");
            assertThat(repository.countDailyAnimals(day.minusDays(2), day.minusDays(2))).isEmpty();
            assertThat(repository.countDailyIntakes(day, day)).isEmpty();
            assertThat(repository.countDailyIntakes(day.minusDays(10), day.minusDays(10)))
                    .extracting(row -> row.getCount()).containsExactly(5L);
            tx.rollback();
        } finally {
            TimeZone.setDefault(previous);
        }
    }
}
