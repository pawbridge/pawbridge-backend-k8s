package com.pawbridge.backend.animal.internal.apms.infrastructure.writer;

import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Animal;
import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Shelter;
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.AnimalStatus;
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.AnimalRepository;
import com.pawbridge.backend.animal.internal.command.infrastructure.search.SearchDocumentWriter;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.batch.item.Chunk;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AnimalItemWriterTest {
    @Test void givenNewAndExistingAnimals__whenWritingChunk__thenInsertOnlyNewAndUseOriginalApmsUpdateFieldsBeforeSearch() throws Exception {
        var repository=mock(AnimalRepository.class);var search=mock(SearchDocumentWriter.class);
        var shelter=Shelter.builder().id(11L).careRegNo("owned").build();
        var fresh=Animal.builder().apmsDesertionNo("new").shelter(shelter).build();
        var old=Animal.builder().id(21L).shelter(shelter).breed("owned breed").happenDate(LocalDate.of(2026,9,10))
                .imageUrl("owned-image").imageUrl2("owned-extra").status(AnimalStatus.PROTECT).build();
        when(repository.saveAll(anyList())).thenAnswer(call -> { ReflectionTestUtils.setField(fresh,"id",31L);return call.getArgument(0); });
        new AnimalItemWriter(search,repository).write(new Chunk<>(List.of(fresh,old)));
        verify(repository).saveAll(List.of(fresh));
        var updates=mockingDetails(repository).getInvocations().stream().filter(i -> i.getMethod().getName().equals("updateAnimalFromApms")).toList();
        assertThat(updates).hasSize(1);Object[] args=updates.getFirst().getArguments();
        assertThat(args).hasSize(18);assertThat(args[0]).isEqualTo(21L);assertThat(args[1]).isSameAs(shelter);
        assertThat(args[2]).isEqualTo("owned breed");assertThat(args[13]).isEqualTo(LocalDate.of(2026,9,10));
        assertThat(args[15]).isEqualTo("owned-image");assertThat(args[16]).isEqualTo("owned-extra");assertThat(args[17]).isEqualTo(AnimalStatus.PROTECT);
        var order=inOrder(repository,search);order.verify(repository).flush();order.verify(search).animal(31L);order.verify(search).animal(21L);
        verify(repository,never()).save(any());
    }

    @Test void givenFlushFailure__whenWritingChunk__thenPropagateBeforeAnyProjection() {
        var repository=mock(AnimalRepository.class);var search=mock(SearchDocumentWriter.class);
        doThrow(new IllegalStateException("synthetic flush failure")).when(repository).flush();
        assertThatThrownBy(() -> new AnimalItemWriter(search,repository).write(new Chunk<>(List.of(Animal.builder().build()))))
                .hasMessage("synthetic flush failure");
        verifyNoInteractions(search);
    }
}
