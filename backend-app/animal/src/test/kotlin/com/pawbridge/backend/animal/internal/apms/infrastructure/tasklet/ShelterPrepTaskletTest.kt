package com.pawbridge.backend.animal.internal.apms.infrastructure.tasklet

import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsAnimalSnapshot
import com.pawbridge.backend.animal.internal.apms.infrastructure.dto.ApmsAnimal
import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Shelter
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.ShelterRepository
import com.pawbridge.backend.animal.internal.command.infrastructure.search.SearchDocumentWriter
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyList
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.batch.core.StepContribution
import org.springframework.batch.core.scope.context.ChunkContext
import org.springframework.batch.repeat.RepeatStatus
import org.springframework.batch.support.transaction.ResourcelessTransactionManager
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate

class ShelterPrepTaskletTest {
    @Test
    fun givenSnapshotFailure__whenPreparingShelters__thenFailBeforeDatabaseAccess() {
        val snapshot = mock(ApmsAnimalSnapshot::class.java)
        val repository = mock(ShelterRepository::class.java)
        val search = mock(SearchDocumentWriter::class.java)
        val manager = ObservedManager()
        `when`(snapshot.animals()).thenThrow(IllegalStateException("provider error"))

        assertThatThrownBy {
            task(snapshot, repository, search, manager).execute(mock(StepContribution::class.java), mock(ChunkContext::class.java))
        }.isInstanceOf(IllegalStateException::class.java)

        verifyNoInteractions(repository, search)
        assertThat(manager.begins).isZero()
    }

    @Test
    fun givenEmptySnapshot__whenPreparingShelters__thenCompleteWithoutWriting() {
        val snapshot = mock(ApmsAnimalSnapshot::class.java)
        val repository = mock(ShelterRepository::class.java)
        val search = mock(SearchDocumentWriter::class.java)
        val manager = ObservedManager()
        `when`(snapshot.animals()).thenReturn(listOf())

        assertThat(
            task(snapshot, repository, search, manager).execute(mock(StepContribution::class.java), mock(ChunkContext::class.java)),
        ).isEqualTo(RepeatStatus.FINISHED)

        verify(repository, never()).saveAll(any<Iterable<Shelter>>())
        verifyNoInteractions(search)
        assertThat(manager.commits).isEqualTo(1)
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun givenOldAnimalWithNewShelterInUpdatedSnapshot__whenPreparing__thenFetchOutsideAndSaveAndSearchInsideOneExplicitTransaction() {
        val snapshot = mock(ApmsAnimalSnapshot::class.java)
        val repository = mock(ShelterRepository::class.java)
        val search = mock(SearchDocumentWriter::class.java)
        val manager = ObservedManager()
        manager.defaultTimeout = 7
        `when`(snapshot.animals()).thenAnswer {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
            assertThat(manager.begins).isZero()
            listOf(animal("new-shelter"), animal("existing-shelter"))
        }
        `when`(repository.findByCareRegNoIn(anyList())).thenAnswer {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue()
            listOf(Shelter.builder().id(50L).careRegNo("existing-shelter").name("Existing management").build())
        }
        `when`(repository.saveAll(anyList<Shelter>())).thenAnswer { call ->
            val shelters: List<Shelter> = call.getArgument(0)
            shelters.forEach { shelter -> ReflectionTestUtils.setField(shelter, "id", 51L) }
            shelters
        }
        doAnswer {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue()
            null
        }.`when`(search).shelter(51L)

        assertThat(
            task(snapshot, repository, search, manager).execute(mock(StepContribution::class.java), mock(ChunkContext::class.java)),
        ).isEqualTo(RepeatStatus.FINISHED)

        val saved = ArgumentCaptor.forClass(List::class.java) as ArgumentCaptor<List<Shelter>>
        verify(repository).saveAll(saved.capture())
        assertThat(saved.value.map { shelter -> shelter.careRegNo }).containsExactly("new-shelter")
        val order = inOrder(repository, search)
        order.verify(repository).flush()
        order.verify(search).shelter(51L)
        assertThat(manager.begins).isEqualTo(1)
        assertThat(manager.timeout).isEqualTo(15)
        assertThat(manager.commits).isEqualTo(1)
        assertThat(manager.defaultTimeout).isEqualTo(7)
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
    }

    @Test
    fun givenCallerWriterTransaction__whenPreparing__thenRejectBeforeSnapshotOrDatabaseWork() {
        val snapshot = mock(ApmsAnimalSnapshot::class.java)
        val repository = mock(ShelterRepository::class.java)
        val search = mock(SearchDocumentWriter::class.java)
        val manager = ObservedManager()
        val prep = task(snapshot, repository, search, manager)

        assertThatThrownBy {
            TransactionTemplate(manager).execute { status ->
                prep.execute(mock(StepContribution::class.java), mock(ChunkContext::class.java))
            }
        }.hasMessage("APMS preparation must start outside the writer transaction")

        verifyNoInteractions(snapshot, repository, search)
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
    }

    @Test
    fun givenSearchFailureAfterShelterSave__whenPreparing__thenRollbackTheSameTransactionAndPropagate() {
        val snapshot = mock(ApmsAnimalSnapshot::class.java)
        val repository = mock(ShelterRepository::class.java)
        val search = mock(SearchDocumentWriter::class.java)
        val manager = ObservedManager()
        `when`(snapshot.animals()).thenReturn(listOf(animal("new-shelter")))
        `when`(repository.saveAll(anyList<Shelter>())).thenAnswer { call ->
            val saved: List<Shelter> = call.getArgument(0)
            ReflectionTestUtils.setField(saved.first(), "id", 51L)
            saved
        }
        doThrow(IllegalStateException("synthetic projection failure")).`when`(search).shelter(51L)

        assertThatThrownBy {
            task(snapshot, repository, search, manager).execute(mock(StepContribution::class.java), mock(ChunkContext::class.java))
        }.hasMessage("synthetic projection failure")

        assertThat(manager.rollbacks).isEqualTo(1)
        assertThat(manager.commits).isZero()
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
    }

    private fun task(
        snapshot: ApmsAnimalSnapshot,
        repository: ShelterRepository,
        search: SearchDocumentWriter,
        manager: ObservedManager,
    ): ShelterPrepTasklet {
        return ShelterPrepTasklet(search, snapshot, repository, manager, 15)
    }

    private fun animal(shelter: String): ApmsAnimal {
        return ApmsAnimal.builder()
            .desertionNo("old")
            .happenDt("20260715")
            .careRegNo(shelter)
            .careNm("Test shelter")
            .careAddr("Test address")
            .build()
    }

    private class ObservedManager : ResourcelessTransactionManager() {
        var begins = 0
        var commits = 0
        var rollbacks = 0
        var timeout = 0

        override fun doBegin(transaction: Any, definition: TransactionDefinition) {
            begins++
            timeout = definition.timeout
            super.doBegin(transaction, definition)
        }

        override fun doCommit(status: DefaultTransactionStatus) {
            commits++
            super.doCommit(status)
        }

        override fun doRollback(status: DefaultTransactionStatus) {
            rollbacks++
            super.doRollback(status)
        }
    }
}
