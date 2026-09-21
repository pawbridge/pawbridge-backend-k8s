package com.pawbridge.animalservice.mypage.service;

import com.pawbridge.animalservice.dto.response.AnimalResponse;
import com.pawbridge.animalservice.entity.Animal;
import com.pawbridge.animalservice.entity.Shelter;
import com.pawbridge.animalservice.enums.AnimalStatus;
import com.pawbridge.animalservice.enums.ApiSource;
import com.pawbridge.animalservice.enums.Gender;
import com.pawbridge.animalservice.enums.NeuterStatus;
import com.pawbridge.animalservice.enums.Species;
import com.pawbridge.animalservice.mapper.AnimalMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("ci")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({MyPageAnimalServiceImpl.class, AnimalMapper.class,
        MyPageAnimalPaginationTest.AuditingConfiguration.class})
class MyPageAnimalPaginationTest {
    @Autowired private TestEntityManager entityManager;
    @Autowired private MyPageAnimalService service;

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 20, 21, 41})
    void countsAndPagesOnlyManualAnimalsFromRequestedShelter(int manualCount) {
        Shelter own = entityManager.persistAndFlush(Shelter.builder()
                .careRegNo("own-shelter").name("Own shelter").build());
        Shelter other = entityManager.persistAndFlush(Shelter.builder()
                .careRegNo("other-shelter").name("Other shelter").build());
        List<Long> expectedIds = new ArrayList<>();
        for (int i = 0; i < manualCount; i++) {
            // A caller-supplied notice number need not start with MAN-.
            expectedIds.add(persist(own, ApiSource.MANUAL, "custom-notice-" + i).getId());
        }
        for (int i = 0; i < 25; i++) {
            // Even a MAN- prefix must not override the stored source.
            persist(own, ApiSource.APMS_ANIMAL, "MAN-imported-" + i);
            persist(other, ApiSource.MANUAL, "MAN-other-" + i);
        }
        persist(own, ApiSource.GYEONGGI, "regional-notice");
        persist(own, ApiSource.UNKNOWN, "unknown-notice");
        entityManager.flush();
        entityManager.clear();
        Collections.reverse(expectedIds);
        int expectedPages = (manualCount + 19) / 20;
        List<Long> actualIds = new ArrayList<>();

        for (int number = 0; number < Math.max(1, expectedPages); number++) {
            var result = service.findByShelterId(own.getId(), PageRequest.of(number, 20,
                    Sort.by(Sort.Direction.DESC, "createdAt", "id")));
            assertThat(result.getTotalElements()).isEqualTo(manualCount);
            assertThat(result.getTotalPages()).isEqualTo(expectedPages);
            assertThat(result.getNumber()).isEqualTo(number);
            assertThat(result.isLast()).isEqualTo(number >= expectedPages - 1);
            assertThat(result.getContent()).hasSize(Math.min(20, manualCount - number * 20))
                    .allSatisfy(animal -> assertThat(animal.getShelterId()).isEqualTo(own.getId()));
            actualIds.addAll(result.getContent().stream().map(AnimalResponse::getId).toList());
        }
        assertThat(actualIds).containsExactlyElementsOf(expectedIds);
    }

    private Animal persist(Shelter shelter, ApiSource source, String noticeNo) {
        return entityManager.persist(Animal.builder()
                .apmsNoticeNo(noticeNo).species(Species.DOG).status(AnimalStatus.PROTECT)
                .gender(Gender.UNKNOWN).neuterStatus(NeuterStatus.UNKNOWN)
                .noticeStartDate(LocalDate.of(2026, 1, 1)).noticeEndDate(LocalDate.of(2026, 1, 10))
                .apiSource(source).shelter(shelter).build());
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableJpaAuditing
    static class AuditingConfiguration { }
}
