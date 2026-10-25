package com.pawbridge.backend.animal.internal.apms.infrastructure.writer

import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Animal
import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Shelter
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.AnimalStatus
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.AnimalRepository
import com.pawbridge.backend.animal.internal.command.infrastructure.search.SearchDocumentWriter
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyList
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.never
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.batch.item.Chunk
import org.springframework.test.util.ReflectionTestUtils
import java.time.LocalDate

class AnimalItemWriterTest {
    @Test
    fun givenNewAndExistingAnimals__whenWritingChunk__thenInsertOnlyNewAndUseOriginalApmsUpdateFieldsBeforeSearch() {
        val repository = mock(AnimalRepository::class.java)
        val search = mock(SearchDocumentWriter::class.java)
        val shelter = Shelter.builder().id(11L).careRegNo("owned").build()
        val fresh = Animal.builder().apmsDesertionNo("new").shelter(shelter).build()
        val old = Animal.builder().id(21L).shelter(shelter).breed("owned breed").happenDate(LocalDate.of(2026, 9, 10))
            .imageUrl("owned-image").imageUrl2("owned-extra").status(AnimalStatus.PROTECT).build()
        `when`(repository.saveAll(anyList<Animal>())).thenAnswer { call ->
            ReflectionTestUtils.setField(fresh, "id", 31L)
            call.getArgument<Any>(0)
        }

        AnimalItemWriter(search, repository).write(Chunk(listOf(fresh, old)))

        verify(repository).saveAll(listOf(fresh))
        val updates = mockingDetails(repository).invocations
            .filter { invocation -> invocation.method.name == "updateAnimalFromApms" }
        assertThat(updates).hasSize(1)
        val args = updates.first().arguments
        assertThat(args).hasSize(18)
        assertThat(args[0]).isEqualTo(21L)
        assertThat(args[1]).isSameAs(shelter)
        assertThat(args[2]).isEqualTo("owned breed")
        assertThat(args[13]).isEqualTo(LocalDate.of(2026, 9, 10))
        assertThat(args[15]).isEqualTo("owned-image")
        assertThat(args[16]).isEqualTo("owned-extra")
        assertThat(args[17]).isEqualTo(AnimalStatus.PROTECT)
        val order = inOrder(repository, search)
        order.verify(repository).flush()
        order.verify(search).animal(31L)
        order.verify(search).animal(21L)
        verify(repository, never()).save(any())
    }

    @Test
    fun givenFlushFailure__whenWritingChunk__thenPropagateBeforeAnyProjection() {
        val repository = mock(AnimalRepository::class.java)
        val search = mock(SearchDocumentWriter::class.java)
        doThrow(IllegalStateException("synthetic flush failure")).`when`(repository).flush()

        assertThatThrownBy { AnimalItemWriter(search, repository).write(Chunk(listOf(Animal.builder().build()))) }
            .hasMessage("synthetic flush failure")
        verifyNoInteractions(search)
    }
}
