package com.pawbridge.animalservice.entity;

import com.pawbridge.animalservice.batch.writer.AnimalItemWriter;
import com.pawbridge.animalservice.repository.AnimalRepository;
import com.pawbridge.animalservice.enums.AnimalStatus;
import com.pawbridge.animalservice.enums.ApiSource;
import com.pawbridge.animalservice.enums.Gender;
import com.pawbridge.animalservice.enums.NeuterStatus;
import com.pawbridge.animalservice.enums.Species;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.batch.item.Chunk;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.test.context.ActiveProfiles;

import java.sql.SQLIntegrityConstraintViolationException;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("ci")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(AnimalIdentityPersistenceTest.AuditingConfiguration.class)
class AnimalIdentityPersistenceTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private AnimalRepository animalRepository;

    @Test
    void differentDesertionNumbersWithSameNoticeAreStoredSeparately() {
        Shelter shelter = shelter();
        Animal original = entityManager.persistAndFlush(animal("test-animal-1", "test-notice-1", shelter));
        Animal incoming = entityManager.persistAndFlush(animal("test-animal-2", "test-notice-1", shelter));

        entityManager.clear();

        assertThat(incoming.getId()).isNotEqualTo(original.getId());
        assertThat(entityManager.find(Animal.class, original.getId()).getApmsDesertionNo()).isEqualTo("test-animal-1");
        assertThat(entityManager.find(Animal.class, incoming.getId()).getApmsDesertionNo()).isEqualTo("test-animal-2");
        assertThat(entityManager.find(Animal.class, original.getId()).getApmsNoticeNo()).isEqualTo("test-notice-1");
        assertThat(entityManager.find(Animal.class, incoming.getId()).getApmsNoticeNo()).isEqualTo("test-notice-1");
    }

    @Test
    void sameDesertionNumberIsRejectedEvenWithDifferentNotice() {
        Shelter shelter = shelter();
        entityManager.persistAndFlush(animal("test-animal-1", "test-notice-1", shelter));

        assertThatThrownBy(() -> entityManager.persistAndFlush(animal("test-animal-1", "test-notice-2", shelter)))
                .hasRootCauseInstanceOf(SQLIntegrityConstraintViolationException.class);
    }

    @Test
    void updatingOneAnimalDoesNotChangeAnotherWithSameNotice() throws Exception {
        Shelter shelter = shelter();
        Animal original = entityManager.persistAndFlush(animal("test-animal-1", "test-notice-1", shelter));
        Animal incoming = entityManager.persistAndFlush(animal("test-animal-2", "test-notice-1", shelter));

        entityManager.clear();
        incoming.updateStatus(AnimalStatus.PROTECT);
        new AnimalItemWriter(animalRepository).write(new Chunk<>(incoming));
        entityManager.flush();
        entityManager.clear();

        assertThat(entityManager.find(Animal.class, original.getId()).getStatus()).isEqualTo(AnimalStatus.NOTICE);
        assertThat(entityManager.find(Animal.class, incoming.getId()).getStatus()).isEqualTo(AnimalStatus.PROTECT);
        assertThat(entityManager.find(Animal.class, original.getId()).getApmsDesertionNo()).isEqualTo("test-animal-1");
        assertThat(entityManager.find(Animal.class, incoming.getId()).getApmsDesertionNo()).isEqualTo("test-animal-2");
    }

    private Shelter shelter() {
        return entityManager.persistAndFlush(Shelter.builder().careRegNo("test-shelter").name("Test shelter").build());
    }

    private Animal animal(String desertionNo, String noticeNo, Shelter shelter) {
        return Animal.builder()
                .apmsDesertionNo(desertionNo).apmsNoticeNo(noticeNo)
                .species(Species.DOG).status(AnimalStatus.NOTICE).apiSource(ApiSource.APMS_ANIMAL)
                .gender(Gender.MALE).neuterStatus(NeuterStatus.UNKNOWN)
                .noticeStartDate(LocalDate.of(2026, 1, 1)).noticeEndDate(LocalDate.of(2026, 1, 10))
                .shelter(shelter).build();
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableJpaAuditing
    static class AuditingConfiguration {
    }
}
