package com.pawbridge.animalservice.repository;

import com.pawbridge.animalservice.entity.Animal;
import com.pawbridge.animalservice.entity.Shelter;
import com.pawbridge.animalservice.enums.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

@Import(AnimalStatsRepositoryTest.AuditingConfiguration.class)
@DataJpaTest
@ActiveProfiles("ci")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AnimalStatsRepositoryTest {
    @TestConfiguration(proxyBeanMethods = false)
    @EnableJpaAuditing
    static class AuditingConfiguration {}

    @Autowired TestEntityManager em;
    @Autowired AnimalStatsRepository repository;
    private final LocalDate end = LocalDate.of(2026, 9, 12);
    private final LocalDate start = end.minusDays(29);

    @Test
    void givenDifferentNoticeAndUpdateDates__whenCountingToday__thenUseIntakeDate() {
        var shelter = shelter();
        animal("today", end, start, AnimalStatus.PROTECT, shelter);
        animal("old", start.minusDays(1), end, AnimalStatus.ADOPTED, shelter);
        animal("unknown", null, end, AnimalStatus.PROTECT, shelter);
        assertThat(repository.countRescuedToday(end)).isEqualTo(1);
    }

    @Test
    void givenBoundaryAndOldAnimals__whenGroupingStatus__thenIncludeBothIntakeBoundariesOnly() {
        var shelter = shelter();
        animal("start", start, start.minusDays(2), AnimalStatus.ADOPTED, shelter);
        animal("end", end, end.plusDays(2), AnimalStatus.PROTECT, shelter);
        animal("before", start.minusDays(1), end, AnimalStatus.EUTHANIZED, shelter);
        animal("after", end.plusDays(1), end, AnimalStatus.EUTHANIZED, shelter);
        animal("unknown", null, end, AnimalStatus.ADOPTED, shelter);
        assertThat(repository.countByStatus(start, end)).extracting("status", "count")
            .containsExactlyInAnyOrder(tuple(AnimalStatus.ADOPTED, 1L), tuple(AnimalStatus.PROTECT, 1L));
    }

    @Test
    void givenChangedCurrentStatus__whenReadingSameIntakePeriod__thenReflectLatestStatus() {
        var animal = animal("changed", start, end, AnimalStatus.PROTECT, shelter());
        animal.updateStatus(AnimalStatus.ADOPTED);
        em.flush();
        assertThat(repository.countByStatus(start, end)).extracting("status", "count")
            .containsExactly(tuple(AnimalStatus.ADOPTED, 1L));
    }

    @Test
    void givenDifferentNoticeDates__whenGroupingShelters__thenUseSameIntakePeriod() {
        var shelter = shelter();
        animal("start", start, start.minusDays(2), AnimalStatus.ADOPTED, shelter);
        animal("end", end, end.plusDays(2), AnimalStatus.PROTECT, shelter);
        animal("old", start.minusDays(1), end, AnimalStatus.PROTECT, shelter);
        assertThat(repository.countByShelterForRegional(start, end)).extracting("shelterAddress", "count")
            .containsExactly(tuple("서울특별시 종로구", 2L));
    }

    private Shelter shelter() {
        return em.persistAndFlush(Shelter.builder().careRegNo("stats-shelter")
            .name("통계 테스트 보호소").address("서울특별시 종로구").build());
    }

    private Animal animal(String id, LocalDate intake, LocalDate notice, AnimalStatus status, Shelter shelter) {
        return em.persistAndFlush(Animal.builder().apmsDesertionNo(id).apmsNoticeNo("notice-" + id)
            .species(Species.DOG).gender(Gender.MALE).neuterStatus(NeuterStatus.UNKNOWN)
            .apiSource(ApiSource.APMS_ANIMAL).status(status).shelter(shelter)
            .happenDate(intake).noticeStartDate(notice).noticeEndDate(notice.plusDays(10))
            .apmsUpdatedAt(notice.atTime(12, 0)).build());
    }
}
