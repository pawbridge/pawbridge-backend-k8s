package com.pawbridge.animalservice.admin.repository;

import com.pawbridge.animalservice.entity.*;
import com.pawbridge.animalservice.enums.*;
import com.pawbridge.animalservice.repository.AnimalStatsRepository;
import java.time.*;
import org.hibernate.cfg.Configuration;
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "PAWBRIDGE_STATS_TEST_JDBC_URL",
        matches = "jdbc:postgresql://(127\\.0\\.0\\.1|stats-db)(:[0-9]+)?/pawbridge_admin_stats_test")
class AdminIntakeStatsPostgresTest {
    @Test void givenIntakeDatesDifferentFromStorageDate__whenAggregated__thenUseOnlyKnownIntakeDates() {
        try (var factory = new Configuration().addAnnotatedClass(Animal.class).addAnnotatedClass(Shelter.class)
                .setPhysicalNamingStrategy(new CamelCaseToUnderscoresNamingStrategy())
                .setProperty("hibernate.connection.url", System.getenv("PAWBRIDGE_STATS_TEST_JDBC_URL"))
                .setProperty("hibernate.connection.username", "postgres")
                .setProperty("hibernate.connection.password", "")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                .setProperty("hibernate.jdbc.time_zone", "UTC")
                .buildSessionFactory(); var session = factory.openSession()) {
            var tx = session.beginTransaction();
            var day = LocalDate.of(2026, 10, 2);
            var shelter = Shelter.builder().careRegNo("test-only-123").name("검증 보호소").build();
            ReflectionTestUtils.setField(shelter, "createdAt", day.atStartOfDay());
            session.persist(shelter);
            session.persist(animal(shelter, "1", day.minusDays(1)));
            session.persist(animal(shelter, "2", day));
            session.persist(animal(shelter, "3", day));
            session.persist(animal(shelter, "4", null));
            session.persist(animal(shelter, "5", day.plusDays(1)));
            session.flush();
            var repository = new JpaRepositoryFactory(session).getRepository(AdminStatsRepository.class);
            assertThat(repository.countDailyIntakes(day.minusDays(1), day))
                    .extracting(row -> row.getDate() + ":" + row.getCount())
                    .containsExactly(day.minusDays(1) + ":1", day + ":2");
            var publicStats = new JpaRepositoryFactory(session).getRepository(AnimalStatsRepository.class);
            assertThat(publicStats.countByStatus(day.minusDays(1), day))
                    .extracting(row -> row.getCount()).containsExactly(3L);
            tx.rollback();
        }
    }

    private Animal animal(Shelter shelter, String notice, LocalDate intake) {
        var animal = Animal.builder().apmsNoticeNo("test-" + notice).species(Species.DOG)
                .gender(Gender.UNKNOWN).neuterStatus(NeuterStatus.UNKNOWN).status(AnimalStatus.NOTICE)
                .apiSource(ApiSource.APMS_ANIMAL).noticeStartDate(LocalDate.of(2026, 10, 1))
                .noticeEndDate(LocalDate.of(2026, 10, 10)).happenDate(intake).shelter(shelter).build();
        ReflectionTestUtils.setField(animal, "createdAt", LocalDateTime.of(2026, 10, 2, 12, 0));
        return animal;
    }
}
