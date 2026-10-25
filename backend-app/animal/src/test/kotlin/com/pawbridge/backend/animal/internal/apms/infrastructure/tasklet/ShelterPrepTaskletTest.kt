package com.pawbridge.backend.animal.internal.apms.infrastructure.tasklet

import com.pawbridge.backend.animal.internal.apms.infrastructure.ApmsAnimalSnapshot
import com.pawbridge.backend.animal.internal.apms.infrastructure.dto.ApmsAnimal
import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Shelter
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.ShelterRepository
import com.pawbridge.backend.animal.internal.command.infrastructure.search.SearchDocumentWriter
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
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
    fun `스냅샷 조회가 실패할 때__보호소를 준비하면__데이터베이스 접근 전에 실패한다`() {
        // given
        val snapshot = mockk<ApmsAnimalSnapshot>()
        val repository = mockk<ShelterRepository>()
        val search = mockk<SearchDocumentWriter>()
        val manager = ObservedManager()

        every { snapshot.animals() } throws IllegalStateException("provider error")

        // when
        val thrown = assertThatThrownBy {
            task(snapshot, repository, search, manager).execute(mockk<StepContribution>(), mockk<ChunkContext>())
        }

        // then
        thrown.isInstanceOf(IllegalStateException::class.java)
        confirmVerified(repository, search)
        assertThat(manager.begins).isZero()
    }

    @Test
    fun `스냅샷이 비어 있을 때__보호소를 준비하면__아무것도 쓰지 않고 완료한다`() {
        // given
        val snapshot = mockk<ApmsAnimalSnapshot>()
        val repository = mockk<ShelterRepository>()
        val search = mockk<SearchDocumentWriter>()
        val manager = ObservedManager()

        every { snapshot.animals() } returns listOf()
        every { repository.findByCareRegNoIn(any()) } returns listOf()

        // when
        val status = task(snapshot, repository, search, manager).execute(mockk<StepContribution>(), mockk<ChunkContext>())

        // then
        assertThat(status).isEqualTo(RepeatStatus.FINISHED)
        verify(exactly = 0) { repository.saveAll(any<Iterable<Shelter>>()) }
        confirmVerified(search)
        assertThat(manager.commits).isEqualTo(1)
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
    }

    @Test
    fun `갱신된 스냅샷에 기존 동물의 새 보호소가 있을 때__준비하면__조회는 트랜잭션 밖에서 하고 저장과 검색 반영은 하나의 명시적 트랜잭션 안에서 한다`() {
        // given
        val snapshot = mockk<ApmsAnimalSnapshot>()
        val repository = mockk<ShelterRepository>()
        val search = mockk<SearchDocumentWriter>()
        val manager = ObservedManager()
        manager.defaultTimeout = 7

        every { snapshot.animals() } answers {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
            assertThat(manager.begins).isZero()
            listOf(animal("new-shelter"), animal("existing-shelter"))
        }
        every { repository.findByCareRegNoIn(any()) } answers {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue()
            listOf(Shelter.builder().id(50L).careRegNo("existing-shelter").name("Existing management").build())
        }
        every { repository.saveAll(any<List<Shelter>>()) } answers {
            val shelters = firstArg<List<Shelter>>()
            shelters.forEach { shelter -> ReflectionTestUtils.setField(shelter, "id", 51L) }
            shelters
        }
        justRun { repository.flush() }
        every { search.shelter(any()) } answers {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue()
            Unit
        }

        // when
        val status = task(snapshot, repository, search, manager).execute(mockk<StepContribution>(), mockk<ChunkContext>())

        // then
        val saved = slot<List<Shelter>>()
        assertThat(status).isEqualTo(RepeatStatus.FINISHED)
        verify(exactly = 1) { repository.saveAll(capture(saved)) }
        assertThat(saved.captured.map { shelter -> shelter.careRegNo }).containsExactly("new-shelter")
        verifyOrder {
            repository.flush()
            search.shelter(51L)
        }
        verify(exactly = 1) { repository.flush() }
        verify(exactly = 1) { search.shelter(51L) }
        assertThat(manager.begins).isEqualTo(1)
        assertThat(manager.timeout).isEqualTo(15)
        assertThat(manager.commits).isEqualTo(1)
        assertThat(manager.defaultTimeout).isEqualTo(7)
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
    }

    @Test
    fun `호출자가 쓰기 트랜잭션 안에 있을 때__준비하면__스냅샷이나 데이터베이스 작업 전에 거부한다`() {
        // given
        val snapshot = mockk<ApmsAnimalSnapshot>()
        val repository = mockk<ShelterRepository>()
        val search = mockk<SearchDocumentWriter>()
        val manager = ObservedManager()
        val prep = task(snapshot, repository, search, manager)

        // when
        val thrown = assertThatThrownBy {
            TransactionTemplate(manager).execute { status ->
                prep.execute(mockk<StepContribution>(), mockk<ChunkContext>())
            }
        }

        // then
        thrown.hasMessage("APMS preparation must start outside the writer transaction")
        confirmVerified(snapshot, repository, search)
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
    }

    @Test
    fun `보호소 저장 뒤 검색 반영이 실패할 때__준비하면__같은 트랜잭션을 롤백하고 예외를 전파한다`() {
        // given
        val snapshot = mockk<ApmsAnimalSnapshot>()
        val repository = mockk<ShelterRepository>()
        val search = mockk<SearchDocumentWriter>()
        val manager = ObservedManager()

        every { snapshot.animals() } returns listOf(animal("new-shelter"))
        every { repository.findByCareRegNoIn(any()) } returns listOf()
        every { repository.saveAll(any<List<Shelter>>()) } answers {
            val saved = firstArg<List<Shelter>>()
            ReflectionTestUtils.setField(saved.first(), "id", 51L)
            saved
        }
        justRun { repository.flush() }
        every { search.shelter(any()) } throws IllegalStateException("synthetic projection failure")

        // when
        val thrown = assertThatThrownBy {
            task(snapshot, repository, search, manager).execute(mockk<StepContribution>(), mockk<ChunkContext>())
        }

        // then
        thrown.hasMessage("synthetic projection failure")
        verify(exactly = 1) { search.shelter(51L) }
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
