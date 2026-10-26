package com.pawbridge.backend.animal.internal.command.infrastructure.service

import com.pawbridge.backend.animal.api.AnimalCommandActors
import com.pawbridge.backend.animal.api.AnimalCommandActors.Actor
import com.pawbridge.backend.animal.internal.command.infrastructure.dto.request.CreateAnimalRequest
import com.pawbridge.backend.animal.internal.command.infrastructure.dto.request.UpdateAnimalDescriptionRequest
import com.pawbridge.backend.animal.internal.command.infrastructure.dto.request.UpdateAnimalStatusRequest
import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Animal
import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Shelter
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.AnimalStatus
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.ApiSource
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.Gender
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.NeuterStatus
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.Species
import com.pawbridge.backend.animal.internal.command.infrastructure.mapper.AnimalMapper
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.AnimalRepository
import com.pawbridge.backend.animal.internal.command.infrastructure.repository.ShelterRepository
import com.pawbridge.backend.animal.internal.command.infrastructure.search.SearchDocumentWriter
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import io.mockk.verifyOrder
import jakarta.persistence.EntityNotFoundException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Optional
import java.util.stream.Stream
import javax.sql.DataSource

class AnimalCommandServiceTest {
    private val animals = mockk<AnimalRepository>()
    private val shelters = mockk<ShelterRepository>()
    private val actors = mockk<AnimalCommandActors>()
    private val search = mockk<SearchDocumentWriter>()
    private val manager = mockk<JpaTransactionManager>()
    private val transaction = mockk<TransactionStatus>()
    private val writerSource = mockk<DataSource>()
    private val shelterActor = Actor(11L, "ROLE_SHELTER", "care-7", true, false)
    private val shelter = Shelter.builder().id(7L).careRegNo("care-7").name("보호소").build()
    private val animal = Animal.builder()
        .id(42L)
        .shelter(shelter)
        .species(Species.DOG)
        .status(AnimalStatus.PROTECT)
        .apiSource(ApiSource.APMS_ANIMAL)
        .apmsNoticeNo("기존-공고")
        .build()

    @AfterEach
    fun transaction_metadata_does_not_leak() {
        try {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse()
            assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()).isNull()
        } finally {
            clearMockTransactionMetadata()
        }
    }

    /**
     * Metadata-only transaction fixture used as the answer of the manager stubs: repositories and manager stay
     * mocks, with no JDBC resource, but the thread-bound transaction flags that the notice generator reads are real.
     */
    private fun enterTransaction(definition: TransactionDefinition): TransactionStatus {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
        TransactionSynchronizationManager.setActualTransactionActive(true)
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(definition.isReadOnly)
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
            if (definition.isolationLevel == TransactionDefinition.ISOLATION_DEFAULT) null else definition.isolationLevel,
        )
        return transaction
    }

    private fun clearMockTransactionMetadata() {
        TransactionSynchronizationManager.setActualTransactionActive(false)
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false)
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(null)
    }

    /** Plays the part of the database assigning the generated id when the repository saves a new animal. */
    private fun withGeneratedId(created: Animal): Animal {
        ReflectionTestUtils.setField(created, "id", 43L)
        return created
    }

    private fun transactionWithIsolation(isolation: Int): TransactionTemplate {
        val template = TransactionTemplate(manager)
        template.isolationLevel = isolation
        return template
    }

    private fun createService(): AnimalCommandService {
        return AnimalCommandService(
            search, animals, shelters, AnimalMapper(),
            NoticeNumberGenerator(animals), actors, manager,
        )
    }

    private fun request(): CreateAnimalRequest {
        return CreateAnimalRequest.builder()
            .careRegNo("care-7")
            .species(Species.DOG)
            .gender(Gender.MALE)
            .neuterStatus(NeuterStatus.NO)
            .status(AnimalStatus.PROTECT)
            .noticeStartDate(LocalDate.of(2026, 10, 20))
            .noticeEndDate(LocalDate.of(2026, 10, 30))
            .breed("믹스견")
            .description("보호 중")
            .build()
    }

    @Test
    fun `수동 등록 요청일 때__동물을 등록하면__MANUAL 출처와 생성된 공고번호로 저장하고 검색 문서를 갱신한 뒤 커밋한다`() {
        // given
        val request = request()

        every { manager.dataSource } returns writerSource
        every { search.dataSource() } returns writerSource
        every { manager.getTransaction(any()) } answers { enterTransaction(firstArg()) }
        every { manager.commit(any()) } answers { clearMockTransactionMetadata() }
        every { actors.find(any()) } returns shelterActor
        every { shelters.findByCareRegNo(any()) } returns Optional.of(shelter)
        every { animals.lockManualNoticePrefix(any(), any()) } returns 1
        every { animals.findMaxManualNoticeSuffix(any(), any()) } returns 0
        every { animals.save(any<Animal>()) } answers { withGeneratedId(firstArg()) }
        justRun { animals.flush() }
        justRun { search.animal(any()) }

        val service = createService()

        // when
        val response = service.create(11L, request)

        // then
        assertThat(response.id).isEqualTo(43L)
        assertThat(response.apiSource).isEqualTo(ApiSource.MANUAL)
        assertThat(response.apmsNoticeNo).matches("MAN-\\d{6}-000001")
        verifyOrder {
            actors.find(11L)
            animals.save(any<Animal>())
            animals.flush()
            search.animal(43L)
            manager.commit(transaction)
        }
    }

    @Test
    fun `관리자가 다른 보호소에 APMS 출처와 공고번호를 지정할 때__등록하면__그 출처와 공고번호를 보존하고 새 번호를 만들지 않는다`() {
        // given
        val request = request()
        request.apiSource = ApiSource.APMS_ANIMAL
        request.apmsNoticeNo = "APMS-공고"

        every { manager.dataSource } returns writerSource
        every { search.dataSource() } returns writerSource
        every { manager.getTransaction(any()) } answers { enterTransaction(firstArg()) }
        every { manager.commit(any()) } answers { clearMockTransactionMetadata() }
        every { actors.find(any()) } returns Actor(11L, "ROLE_ADMIN", "another", true, false)
        every { shelters.findByCareRegNo(any()) } returns Optional.of(shelter)
        every { animals.save(any<Animal>()) } answers { withGeneratedId(firstArg()) }
        justRun { animals.flush() }
        justRun { search.animal(any()) }

        val service = createService()

        // when
        val result = service.create(11L, request)

        // then
        assertThat(result.apiSource).isEqualTo(ApiSource.APMS_ANIMAL)
        assertThat(result.apmsNoticeNo).isEqualTo("APMS-공고")
        verify(exactly = 0) { animals.findMaxManualNoticeSuffix(any(), any()) }
        verify(exactly = 0) { animals.lockManualNoticePrefix(any(), any()) }
    }

    @ParameterizedTest
    @MethodSource("nonManualSourcesAndMissingNotices")
    fun `수동 아닌 출처에 공고번호가 없을 때__등록하면__동물·매퍼·번호 생성기를 건드리기 전에 거부하고 롤백한다`(source: ApiSource, notice: String?) {
        // given
        val request = request()
        request.apiSource = source
        request.apmsNoticeNo = notice
        val mapping = spyk(AnimalMapper())
        val numbers = spyk(NoticeNumberGenerator(animals))

        every { manager.dataSource } returns writerSource
        every { search.dataSource() } returns writerSource
        every { manager.getTransaction(any()) } answers { enterTransaction(firstArg()) }
        every { manager.rollback(any()) } answers { clearMockTransactionMetadata() }
        every { actors.find(any()) } returns shelterActor
        every { shelters.findByCareRegNo(any()) } returns Optional.of(shelter)

        val command = AnimalCommandService(search, animals, shelters, mapping, numbers, actors, manager)

        // when
        val thrown = assertThatThrownBy { command.create(11L, request) }

        // then
        thrown.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("수동 등록 외 데이터는 공고번호가 필요합니다.")
        assertThat(request.apiSource).isEqualTo(source)
        assertThat(request.apmsNoticeNo).isEqualTo(notice)
        confirmVerified(animals, mapping, numbers)
        verify(exactly = 0) { search.animal(any()) }
        verify(exactly = 1) { manager.rollback(transaction) }
        verify(exactly = 0) { manager.commit(any()) }
    }

    @ParameterizedTest
    @MethodSource("manualSourcesAndMissingNotices")
    fun `출처가 없거나 MANUAL이고 공고번호가 비었을 때__등록하면__공고번호를 생성해 MANUAL로 저장한다`(source: ApiSource?, notice: String?) {
        // given
        val request = request()
        request.apiSource = source
        request.apmsNoticeNo = notice

        every { manager.dataSource } returns writerSource
        every { search.dataSource() } returns writerSource
        every { manager.getTransaction(any()) } answers { enterTransaction(firstArg()) }
        every { manager.commit(any()) } answers { clearMockTransactionMetadata() }
        every { actors.find(any()) } returns shelterActor
        every { shelters.findByCareRegNo(any()) } returns Optional.of(shelter)
        every { animals.lockManualNoticePrefix(any(), any()) } returns 1
        every { animals.findMaxManualNoticeSuffix(any(), any()) } returns 0
        every { animals.save(any<Animal>()) } answers { withGeneratedId(firstArg()) }
        justRun { animals.flush() }
        justRun { search.animal(any()) }

        val service = createService()

        // when
        val result = service.create(11L, request)

        // then
        assertThat(result.apiSource).isEqualTo(ApiSource.MANUAL)
        assertThat(result.apmsNoticeNo).matches("MAN-\\d{6}-000001")
        verify(exactly = 1) { animals.findMaxManualNoticeSuffix(any(), any()) }
        verify(exactly = 1) { manager.commit(transaction) }
    }

    @ParameterizedTest
    @EnumSource(ApiSource::class)
    fun `공고번호가 주어졌을 때__각 출처로 등록하면__출처를 보존하고 대체 번호를 만들지 않는다`(source: ApiSource) {
        // given
        val request = request()
        request.apiSource = source
        request.apmsNoticeNo = "supplied-notice-7"

        every { manager.dataSource } returns writerSource
        every { search.dataSource() } returns writerSource
        every { manager.getTransaction(any()) } answers { enterTransaction(firstArg()) }
        every { manager.commit(any()) } answers { clearMockTransactionMetadata() }
        every { actors.find(any()) } returns shelterActor
        every { shelters.findByCareRegNo(any()) } returns Optional.of(shelter)
        every { animals.save(any<Animal>()) } answers { withGeneratedId(firstArg()) }
        justRun { animals.flush() }
        justRun { search.animal(any()) }

        val service = createService()

        // when
        val result = service.create(11L, request)

        // then
        assertThat(result.apiSource).isEqualTo(source)
        assertThat(result.apmsNoticeNo).isEqualTo("supplied-notice-7")
        verify(exactly = 0) { animals.findMaxManualNoticeSuffix(any(), any()) }
        verify(exactly = 0) { animals.lockManualNoticePrefix(any(), any()) }
        verify(exactly = 1) { manager.commit(transaction) }
    }

    @Test
    fun `상태가 바뀔 때__상태를 수정하면__flush 중 갱신된 타임스탬프를 응답에 담는다`() {
        // given
        val before = LocalDateTime.of(2026, 10, 20, 10, 0)
        val after = before.plusMinutes(1)
        ReflectionTestUtils.setField(animal, "updatedAt", before)
        val request = UpdateAnimalStatusRequest()
        request.newStatus = AnimalStatus.ADOPTED

        every { manager.dataSource } returns writerSource
        every { search.dataSource() } returns writerSource
        every { manager.getTransaction(any()) } answers { enterTransaction(firstArg()) }
        every { manager.commit(any()) } answers { clearMockTransactionMetadata() }
        every { actors.find(any()) } returns shelterActor
        every { animals.findByIdForUpdate(any()) } returns Optional.of(animal)
        every { animals.flush() } answers { ReflectionTestUtils.setField(animal, "updatedAt", after) }

        val service = createService()

        // when
        val result = service.updateStatus(11L, 42L, request)

        // then
        assertThat(result.status).isEqualTo(AnimalStatus.ADOPTED)
        assertThat(result.updatedAt).isEqualTo(after).isNotEqualTo(before)
        verify(exactly = 1) { manager.commit(transaction) }
        verify(exactly = 0) { search.animal(any()) }
    }

    @Test
    fun `flush가 실패할 때__상태를 수정하면__롤백하고 매핑이나 성공 응답 없이 실패를 전파한다`() {
        // given
        val mapping = spyk(AnimalMapper())
        val failure = DataIntegrityViolationException("synthetic flush failure")
        val request = UpdateAnimalStatusRequest()
        request.newStatus = AnimalStatus.ADOPTED

        every { manager.dataSource } returns writerSource
        every { search.dataSource() } returns writerSource
        every { manager.getTransaction(any()) } answers { enterTransaction(firstArg()) }
        every { manager.rollback(any()) } answers { clearMockTransactionMetadata() }
        every { actors.find(any()) } returns shelterActor
        every { animals.findByIdForUpdate(any()) } returns Optional.of(animal)
        every { animals.flush() } throws failure

        val command = AnimalCommandService(
            search, animals, shelters, mapping,
            NoticeNumberGenerator(animals), actors, manager,
        )

        // when
        val thrown = assertThatThrownBy { command.updateStatus(11L, 42L, request) }

        // then
        thrown.isSameAs(failure)
        verify(exactly = 0) { mapping.toDetailResponse(any()) }
        verify(exactly = 1) { manager.rollback(transaction) }
        verify(exactly = 0) { manager.commit(any()) }
        verify(exactly = 0) { search.animal(any()) }
    }

    @Test
    fun `APMS 동물의 상태가 그대로일 때__상태를 수정하면__수정을 허용하고 검색 문서를 갱신하지 않는다`() {
        // given
        val request = UpdateAnimalStatusRequest()
        request.newStatus = AnimalStatus.PROTECT

        every { manager.dataSource } returns writerSource
        every { search.dataSource() } returns writerSource
        every { manager.getTransaction(any()) } answers { enterTransaction(firstArg()) }
        every { manager.commit(any()) } answers { clearMockTransactionMetadata() }
        every { actors.find(any()) } returns shelterActor
        every { animals.findByIdForUpdate(any()) } returns Optional.of(animal)
        justRun { animals.flush() }

        val service = createService()

        // when
        val response = service.updateStatus(11L, 42L, request)

        // then
        assertThat(response.status).isEqualTo(AnimalStatus.PROTECT)
        verify(exactly = 0) { search.animal(any()) }
        verify(exactly = 1) { manager.commit(transaction) }
    }

    @Test
    fun `APMS 동물일 때__설명을 수정하면__설명을 바꾸고 검색 문서를 갱신한 뒤 커밋한다`() {
        // given
        val request = UpdateAnimalDescriptionRequest()
        request.description = "새 설명"

        every { manager.dataSource } returns writerSource
        every { search.dataSource() } returns writerSource
        every { manager.getTransaction(any()) } answers { enterTransaction(firstArg()) }
        every { manager.commit(any()) } answers { clearMockTransactionMetadata() }
        every { actors.find(any()) } returns shelterActor
        every { animals.findByIdForUpdate(any()) } returns Optional.of(animal)
        justRun { animals.flush() }
        justRun { search.animal(any()) }

        val service = createService()

        // when
        val response = service.updateDescription(11L, 42L, request)

        // then
        assertThat(response.description).isEqualTo("새 설명")
        verifyOrder {
            animals.flush()
            search.animal(42L)
            manager.commit(transaction)
        }
    }

    @Test
    fun `소유자일 때__삭제하면__현재 소유자를 확인하고 검색 정리 없이 삭제한 뒤 커밋한다`() {
        // given
        every { manager.dataSource } returns writerSource
        every { search.dataSource() } returns writerSource
        every { manager.getTransaction(any()) } answers { enterTransaction(firstArg()) }
        every { manager.commit(any()) } answers { clearMockTransactionMetadata() }
        every { actors.find(any()) } returns shelterActor
        every { animals.findByIdForUpdate(any()) } returns Optional.of(animal)
        justRun { animals.deleteById(any()) }

        val service = createService()

        // when
        service.delete(11L, 42L)

        // then
        verify(exactly = 1) { animals.deleteById(42L) }
        verify(exactly = 0) { search.animal(any()) }
        verify(exactly = 1) { manager.commit(transaction) }
    }

    @ParameterizedTest
    @MethodSource("deniedActors")
    fun `사용할 수 없거나 권한 없는 현재 행위자일 때__등록하면__동물이나 보호소 조회 없이 거부하고 롤백한다`(actor: Actor?) {
        // given
        every { manager.dataSource } returns writerSource
        every { search.dataSource() } returns writerSource
        every { manager.getTransaction(any()) } answers { enterTransaction(firstArg()) }
        every { manager.rollback(any()) } answers { clearMockTransactionMetadata() }
        every { actors.find(any()) } returns actor

        val service = createService()

        // when
        val thrown = assertThatThrownBy { service.create(11L, request()) }

        // then
        thrown.isInstanceOf(AnimalCommandForbiddenException::class.java)
        confirmVerified(animals, shelters)
        verify(exactly = 0) { search.animal(any()) }
        verify(exactly = 1) { manager.rollback(transaction) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["ADMIN", "SHELTER"])
    fun `접두사 없는 역할 별칭일 때__등록하면__동물이나 보호소 조회 없이 거부하고 롤백한다`(role: String) {
        // given
        every { manager.dataSource } returns writerSource
        every { search.dataSource() } returns writerSource
        every { manager.getTransaction(any()) } answers { enterTransaction(firstArg()) }
        every { manager.rollback(any()) } answers { clearMockTransactionMetadata() }
        every { actors.find(any()) } returns Actor(11L, role, "care-7", true, false)

        val service = createService()

        // when
        val thrown = assertThatThrownBy { service.create(11L, request()) }

        // then
        thrown.isInstanceOf(AnimalCommandForbiddenException::class.java)
        confirmVerified(animals, shelters)
        verify(exactly = 0) { search.animal(any()) }
        verify(exactly = 1) { manager.rollback(transaction) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["create", "status", "description", "delete"])
    fun `다른 보호소의 행위자일 때__어떤 명령이든 실행하면__거부하고 저장·삭제·검색 갱신 없이 상태를 그대로 두고 롤백한다`(command: String) {
        // given
        every { manager.dataSource } returns writerSource
        every { search.dataSource() } returns writerSource
        every { manager.getTransaction(any()) } answers { enterTransaction(firstArg()) }
        every { manager.rollback(any()) } answers { clearMockTransactionMetadata() }
        every { actors.find(any()) } returns Actor(11L, "ROLE_SHELTER", "other-care", true, false)
        every { animals.findByIdForUpdate(any()) } returns Optional.of(animal)

        val service = createService()

        // when
        val thrown = assertThatThrownBy { invoke(service, command, 11L) }

        // then
        thrown.isInstanceOf(AnimalCommandForbiddenException::class.java)
        verify(exactly = 0) { animals.save(any<Animal>()) }
        verify(exactly = 0) { animals.deleteById(any()) }
        verify(exactly = 0) { search.animal(any()) }
        assertThat(animal.description).isNull()
        assertThat(animal.status).isEqualTo(AnimalStatus.PROTECT)
        verify(exactly = 1) { manager.rollback(transaction) }
    }

    @Test
    fun `인증된 주체가 없을 때__삭제하면__행위자나 동물 조회 없이 거부하고 롤백한다`() {
        // given
        every { manager.dataSource } returns writerSource
        every { search.dataSource() } returns writerSource
        every { manager.getTransaction(any()) } answers { enterTransaction(firstArg()) }
        every { manager.rollback(any()) } answers { clearMockTransactionMetadata() }

        val service = createService()

        // when
        val thrown = assertThatThrownBy { service.delete(null, 42L) }

        // then
        thrown.isInstanceOf(AnimalCommandForbiddenException::class.java)
        confirmVerified(actors, animals)
        verify(exactly = 1) { manager.rollback(transaction) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["status", "description", "delete"])
    fun `동물이 없을 때__상태·설명·삭제 명령을 실행하면__원래의 엔티티 없음 실패를 유지하고 롤백한다`(command: String) {
        // given
        every { manager.dataSource } returns writerSource
        every { search.dataSource() } returns writerSource
        every { manager.getTransaction(any()) } answers { enterTransaction(firstArg()) }
        every { manager.rollback(any()) } answers { clearMockTransactionMetadata() }
        every { actors.find(any()) } returns shelterActor
        every { animals.findByIdForUpdate(any()) } returns Optional.empty()

        val service = createService()

        // when
        val thrown = assertThatThrownBy { invoke(service, command, 11L) }

        // then
        thrown.isInstanceOf(EntityNotFoundException::class.java)
        verify(exactly = 1) { manager.rollback(transaction) }
    }

    @Test
    fun `보호소가 없을 때__등록하면__저장 전에 원래의 실패를 유지하고 롤백한다`() {
        // given
        every { manager.dataSource } returns writerSource
        every { search.dataSource() } returns writerSource
        every { manager.getTransaction(any()) } answers { enterTransaction(firstArg()) }
        every { manager.rollback(any()) } answers { clearMockTransactionMetadata() }
        every { actors.find(any()) } returns shelterActor
        every { shelters.findByCareRegNo(any()) } returns Optional.empty()

        val service = createService()

        // when
        val thrown = assertThatThrownBy { service.create(11L, request()) }

        // then
        thrown.isInstanceOf(EntityNotFoundException::class.java)
        confirmVerified(animals)
        verify(exactly = 1) { manager.rollback(transaction) }
    }

    @Test
    fun `검색 갱신이 실패할 때__등록하면__쓰기 트랜잭션을 롤백한다`() {
        // given
        every { manager.dataSource } returns writerSource
        every { search.dataSource() } returns writerSource
        every { manager.getTransaction(any()) } answers { enterTransaction(firstArg()) }
        every { manager.rollback(any()) } answers { clearMockTransactionMetadata() }
        every { actors.find(any()) } returns shelterActor
        every { shelters.findByCareRegNo(any()) } returns Optional.of(shelter)
        every { animals.lockManualNoticePrefix(any(), any()) } returns 1
        every { animals.findMaxManualNoticeSuffix(any(), any()) } returns 0
        every { animals.save(any<Animal>()) } answers { withGeneratedId(firstArg()) }
        justRun { animals.flush() }
        every { search.animal(any()) } throws IllegalStateException("projection failed")

        val service = createService()

        // when
        val thrown = assertThatThrownBy { service.create(11L, request()) }

        // then
        thrown.isInstanceOf(IllegalStateException::class.java)
        verify(exactly = 1) { search.animal(43L) }
        verify(exactly = 1) { manager.rollback(transaction) }
        verify(exactly = 0) { manager.commit(any()) }
    }

    @Test
    fun `FK 때문에 삭제가 실패할 때__삭제하면__실패를 전파하고 롤백한다`() {
        // given
        every { manager.dataSource } returns writerSource
        every { search.dataSource() } returns writerSource
        every { manager.getTransaction(any()) } answers { enterTransaction(firstArg()) }
        every { manager.rollback(any()) } answers { clearMockTransactionMetadata() }
        every { actors.find(any()) } returns shelterActor
        every { animals.findByIdForUpdate(any()) } returns Optional.of(animal)
        every { animals.deleteById(any()) } throws DataIntegrityViolationException("referenced")

        val service = createService()

        // when
        val thrown = assertThatThrownBy { service.delete(11L, 42L) }

        // then
        thrown.isInstanceOf(DataIntegrityViolationException::class.java)
        verify(exactly = 1) { animals.deleteById(42L) }
        verify(exactly = 1) { manager.rollback(transaction) }
    }

    @Test
    fun `검색과 JPA가 서로 다른 쓰기 DataSource를 쓸 때__서비스를 만들면__작업 전에 실패한다`() {
        // given
        every { manager.dataSource } returns mockk<DataSource>()
        every { search.dataSource() } returns writerSource

        // when
        val thrown = assertThatThrownBy { createService() }

        // then
        thrown.isInstanceOf(IllegalArgumentException::class.java)
    }

    @ParameterizedTest
    @ValueSource(strings = ["status", "description", "delete"])
    fun `소유한 동물일 때__상태·설명·삭제를 실행하면__사용자 조회 뒤 동물 쓰기 잠금을 잡고 잠금 없는 조회는 하지 않는다`(command: String) {
        // given
        every { manager.dataSource } returns writerSource
        every { search.dataSource() } returns writerSource
        every { manager.getTransaction(any()) } answers { enterTransaction(firstArg()) }
        every { manager.commit(any()) } answers { clearMockTransactionMetadata() }
        every { actors.find(any()) } returns shelterActor
        every { animals.findByIdForUpdate(any()) } returns Optional.of(animal)
        justRun { animals.flush() }
        justRun { search.animal(any()) }
        justRun { animals.deleteById(any()) }

        val service = createService()

        // when
        invoke(service, command, 11L)

        // then
        verifyOrder {
            actors.find(11L)
            animals.findByIdForUpdate(42L)
        }
        verify(exactly = 0) { animals.findById(any()) }
        verify(exactly = 1) { manager.commit(transaction) }
    }

    @Test
    fun `최대 순번이 3일 때__공고번호를 생성하면__같은 날 advisory lock을 먼저 잡고 실시간 최대값 다음 번호를 돌려준다`() {
        // given
        every { manager.getTransaction(any()) } answers { enterTransaction(firstArg()) }
        every { manager.commit(any()) } answers { clearMockTransactionMetadata() }
        every { animals.lockManualNoticePrefix(any(), any()) } returns 1
        every { animals.findMaxManualNoticeSuffix(any(), any()) } returns 3

        // when
        val number = transactionWithIsolation(TransactionDefinition.ISOLATION_READ_COMMITTED)
            .execute { status -> NoticeNumberGenerator(animals).generate() }

        // then
        assertThat(number).matches("MAN-\\d{6}-000004")
        val prefix = number!!.substring(0, number.length - 6)
        val day = Integer.parseInt(number.substring(4, 10))
        verifyOrder {
            animals.lockManualNoticePrefix(0x5042414E, day)
            animals.findMaxManualNoticeSuffix(prefix + "%", "^" + prefix + "([0-9]{6})$")
        }
        verify(exactly = 0) { animals.save(any<Animal>()) }
        verify(exactly = 0) { animals.flush() }
        verify(exactly = 0) { search.animal(any()) }
        verify(exactly = 1) { manager.commit(transaction) }
    }

    @Test
    fun `일일 순번이 소진됐을 때__공고번호를 생성하면__일곱 자리 번호를 만들지 않고 실패한다`() {
        // given
        every { manager.getTransaction(any()) } answers { enterTransaction(firstArg()) }
        every { manager.rollback(any()) } answers { clearMockTransactionMetadata() }
        every { animals.lockManualNoticePrefix(any(), any()) } returns 1
        every { animals.findMaxManualNoticeSuffix(any(), any()) } returns 999999

        // when
        val thrown = assertThatThrownBy {
            transactionWithIsolation(TransactionDefinition.ISOLATION_READ_COMMITTED)
                .execute { status -> NoticeNumberGenerator(animals).generate() }
        }

        // then
        thrown.isInstanceOf(IllegalStateException::class.java).hasMessage("수동 등록 공고번호의 일일 한도를 초과했습니다.")
        verify(exactly = 0) { animals.save(any<Animal>()) }
        verify(exactly = 0) { animals.flush() }
        verify(exactly = 0) { search.animal(any()) }
        verify(exactly = 1) { manager.rollback(transaction) }
        verify(exactly = 0) { manager.commit(any()) }
    }

    @Test
    fun `트랜잭션 밖일 때__공고번호를 생성하면__저장소 호출 전에 실패한다`() {
        // given

        // when
        val thrown = assertThatThrownBy { NoticeNumberGenerator(animals).generate() }

        // then
        thrown.isInstanceOf(IllegalStateException::class.java)
            .hasMessage("수동 공고번호는 쓰기 트랜잭션 안에서 생성해야 합니다.")
        confirmVerified(animals)
        verify(exactly = 0) { search.animal(any()) }
    }

    @ParameterizedTest
    @ValueSource(ints = [-1, 1, 4, 8])
    fun `READ_COMMITTED가 명시되지 않았을 때__공고번호를 생성하면__잠금이나 최대값 조회 전에 거부한다`(isolation: Int) {
        // given
        every { manager.getTransaction(any()) } answers { enterTransaction(firstArg()) }
        every { manager.rollback(any()) } answers { clearMockTransactionMetadata() }

        // when
        val thrown = assertThatThrownBy {
            transactionWithIsolation(isolation).execute { status -> NoticeNumberGenerator(animals).generate() }
        }

        // then
        thrown.isInstanceOf(IllegalStateException::class.java)
            .hasMessage("수동 공고번호에는 명시적인 READ_COMMITTED 격리 수준이 필요합니다.")
        confirmVerified(animals)
        verify(exactly = 0) { search.animal(any()) }
        verify(exactly = 1) { manager.rollback(transaction) }
        verify(exactly = 0) { manager.commit(any()) }
    }

    @Test
    fun `읽기 전용 트랜잭션일 때__공고번호를 생성하면__잠금이나 최대값 조회 전에 거부한다`() {
        // given
        val template = transactionWithIsolation(TransactionDefinition.ISOLATION_READ_COMMITTED)
        template.isReadOnly = true

        every { manager.getTransaction(any()) } answers { enterTransaction(firstArg()) }
        every { manager.rollback(any()) } answers { clearMockTransactionMetadata() }

        // when
        val thrown = assertThatThrownBy { template.execute { status -> NoticeNumberGenerator(animals).generate() } }

        // then
        thrown.isInstanceOf(IllegalStateException::class.java)
            .hasMessage("수동 공고번호는 쓰기 트랜잭션 안에서 생성해야 합니다.")
        confirmVerified(animals)
        verify(exactly = 0) { search.animal(any()) }
        verify(exactly = 1) { manager.rollback(transaction) }
        verify(exactly = 0) { manager.commit(any()) }
    }

    private fun invoke(service: AnimalCommandService, command: String, userId: Long?) {
        when (command) {
            "create" -> service.create(userId, request())
            "status" -> {
                val request = UpdateAnimalStatusRequest()
                request.newStatus = AnimalStatus.ADOPTED
                service.updateStatus(userId, 42L, request)
            }
            "description" -> {
                val request = UpdateAnimalDescriptionRequest()
                request.description = "수정"
                service.updateDescription(userId, 42L, request)
            }
            "delete" -> service.delete(userId, 42L)
            else -> throw AssertionError(command)
        }
    }

    companion object {
        @JvmStatic
        fun nonManualSourcesAndMissingNotices(): Stream<Arguments> {
            return ApiSource.values().filter { source -> source != ApiSource.MANUAL }
                .flatMap { source ->
                    listOf<String?>(null, "", " \t\n").map { notice -> Arguments.of(source, notice) }
                }
                .stream()
        }

        @JvmStatic
        fun manualSourcesAndMissingNotices(): Stream<Arguments> {
            return listOf<ApiSource?>(null, ApiSource.MANUAL)
                .flatMap { source ->
                    listOf<String?>(null, "", " \t\n").map { notice -> Arguments.of(source, notice) }
                }
                .stream()
        }

        @JvmStatic
        fun deniedActors(): Stream<Actor?> = Stream.of(
            null,
            Actor(11L, "ROLE_USER", "care-7", true, false),
            Actor(11L, "ROLE_ADMIN", null, false, false),
            Actor(11L, "ROLE_ADMIN", null, true, true),
            Actor(12L, "ROLE_ADMIN", null, true, false),
            Actor(11L, null, "care-7", true, false),
            Actor(11L, "ROLE_SHELTER", null, true, false),
        )
    }
}
