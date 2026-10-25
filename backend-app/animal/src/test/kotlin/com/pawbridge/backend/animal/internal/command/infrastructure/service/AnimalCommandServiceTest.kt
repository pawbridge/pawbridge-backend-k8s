package com.pawbridge.backend.animal.internal.command.infrastructure.service

import com.pawbridge.backend.animal.anyNonNull
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
import jakarta.persistence.EntityNotFoundException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
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
    private val animals: AnimalRepository = mock(AnimalRepository::class.java)
    private val shelters: ShelterRepository = mock(ShelterRepository::class.java)
    private val actors: AnimalCommandActors = mock(AnimalCommandActors::class.java)
    private val search: SearchDocumentWriter = mock(SearchDocumentWriter::class.java)
    private val manager: JpaTransactionManager = mock(JpaTransactionManager::class.java)
    private val transaction: TransactionStatus = mock(TransactionStatus::class.java)
    private val shelter = Shelter.builder().id(7L).careRegNo("care-7").name("보호소").build()
    private val animal = Animal.builder()
        .id(42L)
        .shelter(shelter)
        .species(Species.DOG)
        .status(AnimalStatus.PROTECT)
        .apiSource(ApiSource.APMS_ANIMAL)
        .apmsNoticeNo("기존-공고")
        .build()
    private lateinit var service: AnimalCommandService

    @BeforeEach
    fun setUp() {
        val source = mock(DataSource::class.java)
        `when`(manager.dataSource).thenReturn(source)
        `when`(search.dataSource()).thenReturn(source)
        // Metadata-only transaction fixture: repositories and manager remain mocks, with no JDBC resource.
        `when`(manager.getTransaction(any())).thenAnswer { invocation ->
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
            val definition: TransactionDefinition = invocation.getArgument(0)
            TransactionSynchronizationManager.setActualTransactionActive(true)
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(definition.isReadOnly)
            TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
                if (definition.isolationLevel == TransactionDefinition.ISOLATION_DEFAULT) null else definition.isolationLevel,
            )
            transaction
        }
        doAnswer {
            clearMockTransactionMetadata()
            null
        }.`when`(manager).commit(transaction)
        doAnswer {
            clearMockTransactionMetadata()
            null
        }.`when`(manager).rollback(transaction)
        `when`(actors.find(11L)).thenReturn(Actor(11L, "ROLE_SHELTER", "care-7", true, false))
        `when`(animals.findByIdForUpdate(42L)).thenReturn(Optional.of(animal))
        `when`(shelters.findByCareRegNo("care-7")).thenReturn(Optional.of(shelter))
        `when`(animals.save(any<Animal>())).thenAnswer { invocation ->
            val created: Animal = invocation.getArgument(0)
            ReflectionTestUtils.setField(created, "id", 43L)
            created
        }
        service = createService()
    }

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

    private fun clearMockTransactionMetadata() {
        TransactionSynchronizationManager.setActualTransactionActive(false)
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false)
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(null)
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
    fun create_defaults_manual_and_refreshes_search() {
        val response = service.create(11L, request())

        assertThat(response.id).isEqualTo(43L)
        assertThat(response.apiSource).isEqualTo(ApiSource.MANUAL)
        assertThat(response.apmsNoticeNo).matches("MAN-\\d{6}-000001")
        val order = inOrder(actors, animals, search, manager)
        order.verify(actors).find(11L)
        order.verify(animals).save(any<Animal>())
        order.verify(animals).flush()
        order.verify(search).animal(43L)
        order.verify(manager).commit(transaction)
    }

    @Test
    fun administrator_can_create_for_other_shelter_and_preserve_apms_source_and_notice() {
        `when`(actors.find(11L)).thenReturn(Actor(11L, "ROLE_ADMIN", "another", true, false))
        val request = request()
        request.apiSource = ApiSource.APMS_ANIMAL
        request.apmsNoticeNo = "APMS-공고"

        val result = service.create(11L, request)

        assertThat(result.apiSource).isEqualTo(ApiSource.APMS_ANIMAL)
        assertThat(result.apmsNoticeNo).isEqualTo("APMS-공고")
        verify(animals, never()).findMaxManualNoticeSuffix(anyString(), anyString())
        verify(animals, never()).lockManualNoticePrefix(anyInt(), anyInt())
    }

    @ParameterizedTest
    @MethodSource("nonManualSourcesAndMissingNotices")
    fun non_manual_without_notice_is_rejected_before_animal_search_or_generated_number(source: ApiSource, notice: String?) {
        val request = request()
        request.apiSource = source
        request.apmsNoticeNo = notice
        val mapping = spy(AnimalMapper())
        val numbers = spy(NoticeNumberGenerator(animals))
        val command = AnimalCommandService(search, animals, shelters, mapping, numbers, actors, manager)

        assertThatThrownBy { command.create(11L, request) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("수동 등록 외 데이터는 공고번호가 필요합니다.")

        assertThat(request.apiSource).isEqualTo(source)
        assertThat(request.apmsNoticeNo).isEqualTo(notice)
        verifyNoInteractions(animals, mapping, numbers)
        verify(search, never()).animal(anyLong())
        verify(manager).rollback(transaction)
        verify(manager, never()).commit(any())
    }

    @ParameterizedTest
    @MethodSource("manualSourcesAndMissingNotices")
    fun default_or_explicit_manual_without_notice_still_generates_notice(source: ApiSource?, notice: String?) {
        val request = request()
        request.apiSource = source
        request.apmsNoticeNo = notice

        val result = service.create(11L, request)

        assertThat(result.apiSource).isEqualTo(ApiSource.MANUAL)
        assertThat(result.apmsNoticeNo).matches("MAN-\\d{6}-000001")
        verify(animals).findMaxManualNoticeSuffix(anyString(), anyString())
        verify(manager).commit(transaction)
    }

    @ParameterizedTest
    @EnumSource(ApiSource::class)
    fun supplied_notice_preserves_each_source_without_generating_replacement(source: ApiSource) {
        val request = request()
        request.apiSource = source
        request.apmsNoticeNo = "supplied-notice-7"

        val result = service.create(11L, request)

        assertThat(result.apiSource).isEqualTo(source)
        assertThat(result.apmsNoticeNo).isEqualTo("supplied-notice-7")
        verify(animals, never()).findMaxManualNoticeSuffix(anyString(), anyString())
        verify(animals, never()).lockManualNoticePrefix(anyInt(), anyInt())
        verify(manager).commit(transaction)
    }

    @Test
    fun changed_status_response_contains_timestamp_updated_during_flush() {
        val before = LocalDateTime.of(2026, 10, 20, 10, 0)
        val after = before.plusMinutes(1)
        ReflectionTestUtils.setField(animal, "updatedAt", before)
        doAnswer {
            ReflectionTestUtils.setField(animal, "updatedAt", after)
            null
        }.`when`(animals).flush()
        val request = UpdateAnimalStatusRequest()
        request.newStatus = AnimalStatus.ADOPTED

        val result = service.updateStatus(11L, 42L, request)

        assertThat(result.status).isEqualTo(AnimalStatus.ADOPTED)
        assertThat(result.updatedAt).isEqualTo(after).isNotEqualTo(before)
        verify(manager).commit(transaction)
        verify(search, never()).animal(anyLong())
    }

    @Test
    fun status_flush_failure_rolls_back_and_does_not_map_or_return_success_response() {
        val mapping = spy(AnimalMapper())
        val command = AnimalCommandService(
            search, animals, shelters, mapping,
            NoticeNumberGenerator(animals), actors, manager,
        )
        val failure = DataIntegrityViolationException("synthetic flush failure")
        doThrow(failure).`when`(animals).flush()
        val request = UpdateAnimalStatusRequest()
        request.newStatus = AnimalStatus.ADOPTED

        assertThatThrownBy { command.updateStatus(11L, 42L, request) }.isSameAs(failure)

        verify(mapping, never()).toDetailResponse(anyNonNull<Animal>())
        verify(manager).rollback(transaction)
        verify(manager, never()).commit(any())
        verify(search, never()).animal(anyLong())
    }

    @Test
    fun status_updates_apms_animal_even_when_status_is_unchanged() {
        val request = UpdateAnimalStatusRequest()
        request.newStatus = AnimalStatus.PROTECT

        val response = service.updateStatus(11L, 42L, request)

        assertThat(response.status).isEqualTo(AnimalStatus.PROTECT)
        verify(search, never()).animal(anyLong())
        verify(manager).commit(transaction)
    }

    @Test
    fun description_updates_apms_animal_and_refreshes_search() {
        val request = UpdateAnimalDescriptionRequest()
        request.description = "새 설명"

        assertThat(service.updateDescription(11L, 42L, request).description).isEqualTo("새 설명")

        val order = inOrder(animals, search, manager)
        order.verify(animals).flush()
        order.verify(search).animal(42L)
        order.verify(manager).commit(transaction)
    }

    @Test
    fun delete_checks_current_owner_and_has_no_search_cleanup() {
        service.delete(11L, 42L)

        verify(animals).deleteById(42L)
        verify(search, never()).animal(anyLong())
        verify(manager).commit(transaction)
    }

    @ParameterizedTest
    @MethodSource("deniedActors")
    fun unavailable_or_unauthorized_current_actor_cannot_create(actor: Actor?) {
        `when`(actors.find(11L)).thenReturn(actor)

        assertThatThrownBy { service.create(11L, request()) }.isInstanceOf(AnimalCommandForbiddenException::class.java)

        verifyNoInteractions(animals, shelters)
        verify(search, never()).animal(anyLong())
        verify(manager).rollback(transaction)
    }

    @ParameterizedTest
    @ValueSource(strings = ["ADMIN", "SHELTER"])
    fun unprefixed_role_aliases_are_denied(role: String) {
        `when`(actors.find(11L)).thenReturn(Actor(11L, role, "care-7", true, false))

        assertThatThrownBy { service.create(11L, request()) }.isInstanceOf(AnimalCommandForbiddenException::class.java)

        verifyNoInteractions(animals, shelters)
        verify(search, never()).animal(anyLong())
        verify(manager).rollback(transaction)
    }

    @ParameterizedTest
    @ValueSource(strings = ["create", "status", "description", "delete"])
    fun another_shelter_cannot_mutate_any_command(command: String) {
        `when`(actors.find(11L)).thenReturn(Actor(11L, "ROLE_SHELTER", "other-care", true, false))

        assertThatThrownBy { invoke(command, 11L) }.isInstanceOf(AnimalCommandForbiddenException::class.java)

        verify(animals, never()).save(any<Animal>())
        verify(animals, never()).deleteById(any())
        verify(search, never()).animal(anyLong())
        assertThat(animal.description).isNull()
        assertThat(animal.status).isEqualTo(AnimalStatus.PROTECT)
        verify(manager).rollback(transaction)
    }

    @Test
    fun missing_principal_cannot_invoke_delete() {
        assertThatThrownBy { service.delete(null, 42L) }.isInstanceOf(AnimalCommandForbiddenException::class.java)

        verifyNoInteractions(actors, animals)
        verify(manager).rollback(transaction)
    }

    @ParameterizedTest
    @ValueSource(strings = ["status", "description", "delete"])
    fun missing_animal_keeps_original_entity_not_found_failure(command: String) {
        `when`(animals.findByIdForUpdate(42L)).thenReturn(Optional.empty())

        assertThatThrownBy { invoke(command, 11L) }.isInstanceOf(EntityNotFoundException::class.java)

        verify(manager).rollback(transaction)
    }

    @Test
    fun missing_shelter_keeps_original_failure_before_save() {
        `when`(shelters.findByCareRegNo("care-7")).thenReturn(Optional.empty())

        assertThatThrownBy { service.create(11L, request()) }.isInstanceOf(EntityNotFoundException::class.java)

        verifyNoInteractions(animals)
        verify(manager).rollback(transaction)
    }

    @Test
    fun search_failure_rolls_back_the_writer_transaction() {
        doThrow(IllegalStateException("projection failed")).`when`(search).animal(43L)

        assertThatThrownBy { service.create(11L, request()) }.isInstanceOf(IllegalStateException::class.java)

        verify(manager).rollback(transaction)
        verify(manager, never()).commit(any())
    }

    @Test
    fun delete_fk_failure_propagates_and_rolls_back() {
        doThrow(DataIntegrityViolationException("referenced")).`when`(animals).deleteById(42L)

        assertThatThrownBy { service.delete(11L, 42L) }.isInstanceOf(DataIntegrityViolationException::class.java)

        verify(manager).rollback(transaction)
    }

    @Test
    fun search_and_jpa_using_different_writer_sources_fail_before_work() {
        `when`(manager.dataSource).thenReturn(mock(DataSource::class.java))

        assertThatThrownBy { createService() }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @ParameterizedTest
    @ValueSource(strings = ["status", "description", "delete"])
    fun owned_mutations_take_user_then_animal_write_lock_and_never_read_unlocked(command: String) {
        invoke(command, 11L)

        val order = inOrder(actors, animals)
        order.verify(actors).find(11L)
        order.verify(animals).findByIdForUpdate(42L)
        verify(animals, never()).findById(any())
        verify(manager).commit(transaction)
    }

    @Test
    fun notice_uses_live_max_after_middle_gap_and_same_day_advisory_lock_before_max() {
        `when`(animals.findMaxManualNoticeSuffix(anyString(), anyString())).thenReturn(3)

        val number = transactionWithIsolation(TransactionDefinition.ISOLATION_READ_COMMITTED)
            .execute { status -> NoticeNumberGenerator(animals).generate() }

        assertThat(number).matches("MAN-\\d{6}-000004")
        val prefix = number!!.substring(0, number.length - 6)
        val day = Integer.parseInt(number.substring(4, 10))
        val order = inOrder(animals)
        order.verify(animals).lockManualNoticePrefix(0x5042414E, day)
        order.verify(animals).findMaxManualNoticeSuffix(prefix + "%", "^" + prefix + "([0-9]{6})$")
        verify(animals, never()).save(any<Animal>())
        verify(animals, never()).flush()
        verify(search, never()).animal(anyLong())
        verify(manager).commit(transaction)
    }

    @Test
    fun exhausted_six_digit_suffix_fails_without_emitting_a_seven_digit_number() {
        `when`(animals.findMaxManualNoticeSuffix(anyString(), anyString())).thenReturn(999999)

        assertThatThrownBy {
            transactionWithIsolation(TransactionDefinition.ISOLATION_READ_COMMITTED)
                .execute { status -> NoticeNumberGenerator(animals).generate() }
        }.isInstanceOf(IllegalStateException::class.java).hasMessage("수동 등록 공고번호의 일일 한도를 초과했습니다.")

        verify(animals, never()).save(any<Animal>())
        verify(animals, never()).flush()
        verify(search, never()).animal(anyLong())
        verify(manager).rollback(transaction)
        verify(manager, never()).commit(any())
    }

    @Test
    fun notice_generation_outside_transaction_fails_before_any_repository_call() {
        assertThatThrownBy { NoticeNumberGenerator(animals).generate() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("수동 공고번호는 쓰기 트랜잭션 안에서 생성해야 합니다.")

        verifyNoInteractions(animals)
        verify(search, never()).animal(anyLong())
    }

    @ParameterizedTest
    @ValueSource(ints = [-1, 1, 4, 8])
    fun notice_generation_without_explicit_read_committed_is_rejected_before_lock_or_max(isolation: Int) {
        assertThatThrownBy {
            transactionWithIsolation(isolation).execute { status -> NoticeNumberGenerator(animals).generate() }
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessage("수동 공고번호에는 명시적인 READ_COMMITTED 격리 수준이 필요합니다.")

        verifyNoInteractions(animals)
        verify(search, never()).animal(anyLong())
        verify(manager).rollback(transaction)
        verify(manager, never()).commit(any())
    }

    @Test
    fun notice_generation_in_read_only_transaction_is_rejected_before_lock_or_max() {
        val template = transactionWithIsolation(TransactionDefinition.ISOLATION_READ_COMMITTED)
        template.isReadOnly = true

        assertThatThrownBy { template.execute { status -> NoticeNumberGenerator(animals).generate() } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("수동 공고번호는 쓰기 트랜잭션 안에서 생성해야 합니다.")

        verifyNoInteractions(animals)
        verify(search, never()).animal(anyLong())
        verify(manager).rollback(transaction)
        verify(manager, never()).commit(any())
    }

    private fun invoke(command: String, userId: Long?) {
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
