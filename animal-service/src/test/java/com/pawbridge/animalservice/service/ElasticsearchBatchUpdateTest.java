package com.pawbridge.animalservice.service;

import com.pawbridge.animalservice.batch.ApmsBatchProperties;
import com.pawbridge.animalservice.entity.Animal;
import com.pawbridge.animalservice.enums.AnimalStatus;
import com.pawbridge.animalservice.enums.ApiSource;
import com.pawbridge.animalservice.repository.AnimalDocumentRepository;
import com.pawbridge.animalservice.repository.AnimalRepository;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RedissonClient;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.UpdateQuery;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ElasticsearchBatchUpdateTest {
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void givenUpdatedOldAnimal__whenBatchIndexes__thenUpsertSameDocumentWithoutReplacingVector() {
        var animals = mock(AnimalRepository.class);
        var documents = mock(AnimalDocumentRepository.class);
        var operations = mock(ElasticsearchOperations.class);
        var service = new ElasticsearchIndexService(animals, documents, operations, mock(RedissonClient.class),
                new ResourcelessTransactionManager(), new ApmsBatchProperties());
        var animal = Animal.builder().id(42L).apmsDesertionNo("old-intake").apmsNoticeNo("old-notice")
                .status(AnimalStatus.ADOPTED).apiSource(ApiSource.APMS_ANIMAL).apmsProcessState("종료(입양)")
                .happenDate(LocalDate.of(2026, 7, 15)).apmsUpdatedAt(LocalDateTime.of(2026, 8, 23, 15, 21, 46)).build();
        when(animals.count()).thenReturn(1L);
        when(animals.findAllWithShelter(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(animal)));

        assertThat(service.indexAllAnimals()).isEqualTo(1);

        ArgumentCaptor<List<UpdateQuery>> updates = ArgumentCaptor.forClass(List.class);
        verify(operations).bulkUpdate(updates.capture(), eq(IndexCoordinates.of("animals")));
        assertThat(updates.getValue()).singleElement().satisfies(update -> {
            assertThat(update.getId()).isEqualTo("42");
            assertThat(update.getDocAsUpsert()).isTrue();
            assertThat(update.getDocument()).containsEntry("status", "ADOPTED")
                    .containsEntry("apms_desertion_no", "old-intake")
                    .containsEntry("apms_process_state", "종료(입양)")
                    .containsEntry("happen_date", "2026-07-15")
                    .containsEntry("apms_updated_at", "2026-08-23T15:21:46")
                    .doesNotContainKey("image_vector");
        });
        verifyNoMoreInteractions(operations);
        verifyNoInteractions(documents);
    }

    @Test
    void givenBulkUpdateExceedsConfiguredDeadline__whenBatchIndexes__thenFailStepAndInterruptWorker() throws Exception {
        var animals = mock(AnimalRepository.class);
        var documents = mock(AnimalDocumentRepository.class);
        var operations = mock(ElasticsearchOperations.class);
        var properties = new ApmsBatchProperties();
        properties.setElasticsearchIndexTimeout(Duration.ofMillis(30));
        properties.setElasticsearchCancellationWait(Duration.ofSeconds(1));
        var service = new ElasticsearchIndexService(animals, documents, operations, mock(RedissonClient.class),
                new ResourcelessTransactionManager(), properties);
        var animal = Animal.builder().id(42L).status(AnimalStatus.PROTECT).apiSource(ApiSource.APMS_ANIMAL).build();
        when(animals.count()).thenReturn(1L);
        when(animals.findAllWithShelter(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(animal)));
        doAnswer(invocation -> {
            Thread.sleep(10_000);
            return null;
        }).when(operations).bulkUpdate(anyList(), eq(IndexCoordinates.of("animals")));

        assertThatThrownBy(service::indexAllAnimals)
                .isInstanceOf(ElasticsearchIndexService.ElasticsearchIndexTimeoutException.class)
                .hasMessageContaining("PT0.03S");
    }
}
