package com.pawbridge.animalservice.entity;

import com.pawbridge.animalservice.batch.ApmsAnimalSnapshot;
import com.pawbridge.animalservice.batch.ApmsSyncPlanFactory;
import com.pawbridge.animalservice.batch.processor.AnimalItemProcessor;
import com.pawbridge.animalservice.batch.writer.AnimalItemWriter;
import com.pawbridge.animalservice.client.ApmsApiClient;
import com.pawbridge.animalservice.dto.apms.ApmsAnimal;
import com.pawbridge.animalservice.dto.apms.ApmsBody;
import com.pawbridge.animalservice.dto.apms.ApmsHeader;
import com.pawbridge.animalservice.dto.apms.ApmsItems;
import com.pawbridge.animalservice.dto.apms.ApmsResponse;
import com.pawbridge.animalservice.dto.apms.ApmsRootResponse;
import com.pawbridge.animalservice.enums.AnimalStatus;
import com.pawbridge.animalservice.enums.ApiSource;
import com.pawbridge.animalservice.enums.Gender;
import com.pawbridge.animalservice.enums.NeuterStatus;
import com.pawbridge.animalservice.enums.Species;
import com.pawbridge.animalservice.repository.AnimalRepository;
import com.pawbridge.animalservice.repository.ShelterRepository;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.item.Chunk;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.test.context.ActiveProfiles;

import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

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

    @Autowired
    private ShelterRepository shelterRepository;

    @Test
    void givenOldProtectedAnimal__whenModifiedSnapshotReplayed__thenUpdateSameIdentityWithoutDuplicates() throws Exception {
        Shelter shelter = shelter();
        var processor = new AnimalItemProcessor(animalRepository, shelterRepository);
        var writer = new AnimalItemWriter(animalRepository);
        var initial = ApmsAnimal.builder()
                .desertionNo("old-intake").noticeNo("old-notice").happenDt("20260715")
                .noticeSdt("20260715").noticeEdt("20260725").updTm("2026-07-15 10:00:00.0")
                .careRegNo(shelter.getCareRegNo()).upKindCd("417000").sexCd("M").neuterYn("U")
                .processState("보호중").build();
        processor.beforeStep(null);
        writer.write(new Chunk<>(processor.process(initial)));
        entityManager.flush();
        entityManager.clear();
        Long originalId = animalRepository.findByApmsDesertionNo("old-intake").orElseThrow().getId();

        var clock = Clock.fixed(Instant.parse("2026-09-10T00:00:00Z"), ZoneId.of("Asia/Seoul"));
        var explorer = Mockito.mock(JobExplorer.class);
        var plan = new ApmsSyncPlanFactory(animalRepository, explorer, clock).create("apmsAnimalSyncJob");
        assertThat(plan.historyStart()).isEqualTo(LocalDate.of(2026, 7, 1));
        var api = Mockito.mock(ApmsApiClient.class);
        initial.setProcessState("종료(입양)");
        initial.setUpdTm("2026-08-23 15:21:46.123456");
        Mockito.when(api.getAbandonmentAnimals(ArgumentMatchers.anyString(), ArgumentMatchers.anyInt(),
                ArgumentMatchers.anyInt(), ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
                ArgumentMatchers.isNull(), ArgumentMatchers.isNull(), ArgumentMatchers.eq("json"),
                ArgumentMatchers.nullable(String.class), ArgumentMatchers.nullable(String.class)))
                .thenAnswer(call -> {
                    boolean july = "20260701".equals(call.getArgument(3));
                    var items = july ? List.of(initial) : List.<ApmsAnimal>of();
                    return new ApmsRootResponse<>(new ApmsResponse<>(
                            ApmsHeader.builder().resultCode("00").build(),
                            new ApmsBody<>(new ApmsItems<>(items), "1000", "1", july ? "1" : "0")));
                });
        for (long executionId = 1; executionId <= 2; executionId++) {
            var snapshot = new ApmsAnimalSnapshot(api, "test-key",
                    new JobExecution(executionId, plan.parameters()), 100, 50000);
            processor.beforeStep(null);
            for (var item : snapshot.animals()) writer.write(new Chunk<>(processor.process(item)));
            entityManager.flush();
            entityManager.clear();
        }
        Animal result = animalRepository.findByApmsDesertionNo("old-intake").orElseThrow();
        assertThat(animalRepository.count()).isEqualTo(1);
        assertThat(result.getId()).isEqualTo(originalId);
        assertThat(result.getStatus()).isEqualTo(AnimalStatus.ADOPTED);
        assertThat(result.getApmsUpdatedAt()).isEqualTo(LocalDateTime.of(2026, 8, 23, 15, 21, 46, 123456000));
        assertThat(result.getHappenDate()).isEqualTo(LocalDate.of(2026, 7, 15));
        assertThat(result.getApmsNoticeNo()).isEqualTo("old-notice");
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
