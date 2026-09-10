package com.pawbridge.animalservice.batch.tasklet;

import com.pawbridge.animalservice.batch.ApmsAnimalSnapshot;
import com.pawbridge.animalservice.dto.apms.ApmsAnimal;
import com.pawbridge.animalservice.entity.Shelter;
import com.pawbridge.animalservice.repository.ShelterRepository;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.repeat.RepeatStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShelterPrepTaskletTest {
    @Test
    void givenSnapshotFailure__whenPreparingShelters__thenFailBeforeDatabaseAccess() {
        var snapshot = mock(ApmsAnimalSnapshot.class);
        var repository = mock(ShelterRepository.class);
        when(snapshot.animals()).thenThrow(new IllegalStateException("provider error"));
        assertThatThrownBy(() -> new ShelterPrepTasklet(snapshot, repository).execute(null, null))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void givenEmptySnapshot__whenPreparingShelters__thenCompleteWithoutWriting() {
        var snapshot = mock(ApmsAnimalSnapshot.class);
        var repository = mock(ShelterRepository.class);
        when(snapshot.animals()).thenReturn(List.of());
        assertThat(new ShelterPrepTasklet(snapshot, repository).execute(null, null)).isEqualTo(RepeatStatus.FINISHED);
        verify(repository, never()).saveAll(any());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void givenOldAnimalWithNewShelterInUpdatedSnapshot__whenPreparing__thenSaveShelterBeforeIngestion() {
        var snapshot = mock(ApmsAnimalSnapshot.class);
        var repository = mock(ShelterRepository.class);
        when(snapshot.animals()).thenReturn(List.of(ApmsAnimal.builder().desertionNo("old")
                .happenDt("20260715").careRegNo("new-shelter").careNm("Test shelter").careAddr("Test address").build()));
        new ShelterPrepTasklet(snapshot, repository).execute(null, null);
        ArgumentCaptor<List<Shelter>> saved = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(saved.capture());
        assertThat(saved.getValue()).extracting(Shelter::getCareRegNo).containsExactly("new-shelter");
    }
}
