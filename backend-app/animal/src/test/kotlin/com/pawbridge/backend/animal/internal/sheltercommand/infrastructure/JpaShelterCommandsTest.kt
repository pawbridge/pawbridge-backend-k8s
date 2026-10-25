package com.pawbridge.backend.animal.internal.sheltercommand.infrastructure

import com.pawbridge.backend.animal.api.AnimalCommandActors
import com.pawbridge.backend.animal.api.AnimalCommandActors.Actor
import com.pawbridge.backend.animal.api.AnimalShelterMembers
import com.pawbridge.backend.animal.internal.command.infrastructure.entity.Shelter
import com.pawbridge.backend.animal.internal.command.infrastructure.persistence.PostgresqlRollbackCharsetViolation
import com.pawbridge.backend.animal.internal.command.infrastructure.search.SearchDocumentWriter
import com.pawbridge.backend.animal.internal.sheltercommand.application.ShelterCommandConflictException
import com.pawbridge.backend.animal.internal.sheltercommand.application.ShelterCommandForbiddenException
import com.pawbridge.backend.animal.internal.sheltercommand.application.ShelterCommands
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityNotFoundException
import jakarta.persistence.LockTimeoutException
import jakarta.persistence.Query
import jakarta.persistence.TypedQuery
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.NullAndEmptySource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime
import javax.sql.DataSource

class JpaShelterCommandsTest {
    private val entities: EntityManager = mock(EntityManager::class.java)
    private val manager: JpaTransactionManager = mock(JpaTransactionManager::class.java)
    private val status: TransactionStatus = mock(TransactionStatus::class.java)
    private val search: SearchDocumentWriter = mock(SearchDocumentWriter::class.java)
    private val actors: AnimalCommandActors = mock(AnimalCommandActors::class.java)
    private val members: AnimalShelterMembers = mock(AnimalShelterMembers::class.java)
    private val lock: Query = mock(Query::class.java)

    @Suppress("UNCHECKED_CAST")
    private val animals: TypedQuery<Long> = mock(TypedQuery::class.java) as TypedQuery<Long>
    private val shelter = Shelter.builder()
        .id(7L)
        .careRegNo("care-7")
        .name("보호소")
        .phone("old phone")
        .address("서울")
        .ownerName("대표")
        .organizationName("기관")
        .email("old@example.test")
        .introduction("old intro")
        .adoptionProcedure("old adoption")
        .operatingHours("old hours")
        .build()
    private lateinit var writes: TransactionTemplate
    private lateinit var commands: JpaShelterCommands
    private val create = ShelterCommands.Create(
        "care-new", " 새 보호소 ", "02", "주소", "대표", "기관", "new@example.test", "소개", "절차", "09-18",
    )
    private val update = ShelterCommands.Update("new phone", "new@example.test", "new intro", "new adoption", "new hours")

    @BeforeEach
    fun setUp() {
        val source = mock(DataSource::class.java)
        `when`(manager.dataSource).thenReturn(source)
        `when`(search.dataSource()).thenReturn(source)
        `when`(manager.getTransaction(any())).thenReturn(status)
        writes = TransactionTemplate(manager)
        writes.isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
        commands = JpaShelterCommands(entities, manager, writes, search, actors, members)
        clearInvocations(search)
        `when`(actors.find(10L)).thenReturn(Actor(10L, "ROLE_ADMIN", null, true, false))
        `when`(entities.createNativeQuery("SELECT id FROM pawbridge_animal.shelters WHERE id = :id FOR UPDATE"))
            .thenReturn(lock)
        `when`(lock.setParameter("id", 7L)).thenReturn(lock)
        doReturn(listOf(7L)).`when`(lock).getResultList()
        `when`(entities.find(Shelter::class.java, 7L)).thenReturn(shelter)
        `when`(entities.createQuery("select a.id from Animal a where a.shelter.id = :shelterId", Long::class.javaObjectType))
            .thenReturn(animals)
        `when`(animals.setParameter("shelterId", 7L)).thenReturn(animals)
        `when`(animals.setMaxResults(1)).thenReturn(animals)
        `when`(animals.resultList).thenReturn(listOf())
    }

    @Test
    fun givenAdministrator__whenCreate__thenPreserveEveryOriginalFieldAndFlushBeforeSameTransactionSearch() {
        val created = LocalDateTime.of(2026, 10, 21, 3, 0)
        doAnswer { call ->
            val value: Shelter = call.getArgument(0)
            ReflectionTestUtils.setField(value, "id", 9L)
            ReflectionTestUtils.setField(value, "createdAt", created)
            ReflectionTestUtils.setField(value, "updatedAt", created)
            null
        }.`when`(entities).persist(any(Shelter::class.java))

        val result = commands.create(10L, create)

        assertThat(result).isEqualTo(
            ShelterCommands.Details(
                9L, "care-new", " 새 보호소 ", "02", "주소", "대표", "기관",
                "new@example.test", "소개", "절차", "09-18", created, created,
            ),
        )
        val order = inOrder(actors, entities, search, manager)
        order.verify(actors).find(10L)
        order.verify(entities).persist(any(Shelter::class.java))
        order.verify(entities).flush()
        order.verify(search).shelter(9L)
        order.verify(manager).commit(status)
        verifyNoInteractions(members)
    }

    @Test
    fun givenSearchFailure__whenCreate__thenPropagateAndRequestWriterRollbackWithoutCommit() {
        doThrow(IllegalStateException("search")).`when`(search).shelter(anyLong())
        doAnswer { call ->
            val saved: Shelter = call.getArgument(0)
            ReflectionTestUtils.setField(saved, "id", 9L)
            null
        }.`when`(entities).persist(any())

        assertThatThrownBy { commands.create(10L, create) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("search")

        verify(manager).rollback(status)
        verify(manager, never()).commit(any())
    }

    @Test
    fun givenOwnShelterMember__whenUpdate__thenLockUserBeforeShelterAndReplaceOnlyFiveManagedFields() {
        `when`(actors.find(10L)).thenReturn(Actor(10L, "ROLE_SHELTER", "care-7", true, false))

        val result = commands.update(10L, 7L, update)

        assertThat(result.phone).isEqualTo("new phone")
        assertThat(result.email).isEqualTo("new@example.test")
        assertThat(result.introduction).isEqualTo("new intro")
        assertThat(result.adoptionProcedure).isEqualTo("new adoption")
        assertThat(result.operatingHours).isEqualTo("new hours")
        assertThat(result.careRegNo).isEqualTo("care-7")
        assertThat(result.name).isEqualTo("보호소")
        assertThat(result.address).isEqualTo("서울")
        assertThat(result.ownerName).isEqualTo("대표")
        assertThat(result.organizationName).isEqualTo("기관")
        val order = inOrder(actors, entities)
        order.verify(actors).find(10L)
        order.verify(entities).find(Shelter::class.java, 7L)
        order.verify(entities).flush()
        verifyNoInteractions(search, members)
    }

    @Test
    fun givenAdministratorWithForeignCareNumber__whenUpdate__thenAllowEveryShelter() {
        `when`(actors.find(10L)).thenReturn(Actor(10L, "ROLE_ADMIN", "other", true, false))

        assertThat(commands.update(10L, 7L, update).phone).isEqualTo("new phone")
    }

    @Test
    fun givenOmittedNullableFields__whenUpdate__thenClearAllFiveAsOriginalPutContract() {
        val result = commands.update(10L, 7L, ShelterCommands.Update(null, null, null, null, null))

        assertThat(result.phone).isNull()
        assertThat(result.email).isNull()
        assertThat(result.introduction).isNull()
        assertThat(result.adoptionProcedure).isNull()
        assertThat(result.operatingHours).isNull()
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = [" ", "care-8", "CARE-7"])
    fun givenForeignOrMissingMembership__whenUpdate__thenRejectWithoutMutatingShelter(care: String?) {
        `when`(actors.find(10L)).thenReturn(Actor(10L, "ROLE_SHELTER", care, true, false))

        assertThatThrownBy { commands.update(10L, 7L, update) }
            .isInstanceOf(ShelterCommandForbiddenException::class.java)

        assertThat(shelter.phone).isEqualTo("old phone")
        verifyNoInteractions(search, members)
    }

    @ParameterizedTest
    @ValueSource(strings = ["ROLE_USER", "ROLE_SUPER_ADMIN", "ADMIN", "SHELTER"])
    fun givenUnapprovedRole__whenAnyCommand__thenRejectBeforeLookingUpShelter(role: String) {
        `when`(actors.find(10L)).thenReturn(Actor(10L, role, "care-7", true, false))

        assertThatThrownBy { commands.create(10L, create) }.isInstanceOf(ShelterCommandForbiddenException::class.java)
        assertThatThrownBy { commands.update(10L, 7L, update) }.isInstanceOf(ShelterCommandForbiddenException::class.java)
        assertThatThrownBy { commands.delete(10L, 7L) }.isInstanceOf(ShelterCommandForbiddenException::class.java)

        verifyNoInteractions(entities, search, members)
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "inactive", "pending", "wrong-id"])
    fun givenInvalidCurrentAccount__whenUpdate__thenRejectBeforeShelterLock(kind: String) {
        `when`(actors.find(10L)).thenReturn(
            if (kind == "missing") {
                null
            } else {
                Actor(
                    if (kind == "wrong-id") 11L else 10L,
                    "ROLE_ADMIN",
                    null,
                    kind != "inactive",
                    kind == "pending",
                )
            },
        )

        assertThatThrownBy { commands.update(10L, 7L, update) }.isInstanceOf(ShelterCommandForbiddenException::class.java)

        verifyNoInteractions(entities, search, members)
    }

    @Test
    fun givenUnverifiedIdentity__whenAnyCommand__thenNeverResolveUserOrShelter() {
        assertThatThrownBy { commands.create(null, create) }.isInstanceOf(ShelterCommandForbiddenException::class.java)
        assertThatThrownBy { commands.update(0L, 7L, update) }.isInstanceOf(ShelterCommandForbiddenException::class.java)
        assertThatThrownBy { commands.delete(-1L, 7L) }.isInstanceOf(ShelterCommandForbiddenException::class.java)

        verifyNoInteractions(actors, entities, search, members)
    }

    @Test
    fun givenShelterRole__whenCreateOrDelete__thenRejectBeforeTargetLookup() {
        `when`(actors.find(10L)).thenReturn(Actor(10L, "ROLE_SHELTER", "care-7", true, false))

        assertThatThrownBy { commands.create(10L, create) }.isInstanceOf(ShelterCommandForbiddenException::class.java)
        assertThatThrownBy { commands.delete(10L, 7L) }.isInstanceOf(ShelterCommandForbiddenException::class.java)

        verifyNoInteractions(entities, search, members)
    }

    @Test
    fun givenConnectedAnimal__whenDelete__thenConflictWithoutDeletingOrCheckingMembers() {
        `when`(animals.resultList).thenReturn(listOf(3L))

        assertThatThrownBy { commands.delete(10L, 7L) }.isInstanceOf(ShelterCommandConflictException::class.java)

        verify(animals).setMaxResults(1)
        verify(entities, never()).remove(any())
        verifyNoInteractions(members, search)
    }

    @Test
    fun givenConnectedMember__whenDelete__thenReadAfterShelterLockAndConflictWithoutDeletion() {
        `when`(members.exists("care-7")).thenReturn(true)

        assertThatThrownBy { commands.delete(10L, 7L) }.isInstanceOf(ShelterCommandConflictException::class.java)

        val order = inOrder(actors, entities, animals, members)
        order.verify(actors).find(10L)
        order.verify(entities).find(Shelter::class.java, 7L)
        order.verify(animals).getResultList()
        order.verify(members).exists("care-7")
        verify(entities, never()).remove(any())
        verifyNoInteractions(search)
    }

    @Test
    fun givenNoReferences__whenDelete__thenRemoveOnlyLockedShelterAndCommit() {
        commands.delete(10L, 7L)

        val order = inOrder(actors, entities, members, manager)
        order.verify(actors).find(10L)
        order.verify(entities).find(Shelter::class.java, 7L)
        order.verify(members).exists("care-7")
        order.verify(entities).remove(shelter)
        order.verify(manager).commit(status)
        verifyNoInteractions(search)
    }

    @Test
    fun givenMissingShelter__whenUpdateOrDelete__thenKeepOriginalJpaMissingException() {
        `when`(entities.find(Shelter::class.java, 7L)).thenReturn(null)

        assertThatThrownBy { commands.update(10L, 7L, update) }.isInstanceOf(EntityNotFoundException::class.java)
        assertThatThrownBy { commands.delete(10L, 7L) }.isInstanceOf(EntityNotFoundException::class.java)

        verifyNoInteractions(members, search)
    }

    @ParameterizedTest
    @ValueSource(strings = ["read-only", "repeatable", "default-isolation", "requires-new", "other-manager"])
    fun givenIncompatibleTransactionTemplate__whenConstruct__thenReject(kind: String) {
        when (kind) {
            "read-only" -> writes.isReadOnly = true
            "repeatable" -> writes.isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
            "default-isolation" -> writes.isolationLevel = TransactionDefinition.ISOLATION_DEFAULT
            "requires-new" -> writes.propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
            "other-manager" -> writes.transactionManager = mock(JpaTransactionManager::class.java)
        }

        assertThatThrownBy { JpaShelterCommands(entities, manager, writes, search, actors, members) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun givenCurrentAdministrator__whenDelete__thenNativeForUpdateExcludesApprovalBeforeAnyEntityOrReferenceRead() {
        commands.delete(10L, 7L)

        val order = inOrder(actors, entities, lock, animals, members)
        order.verify(actors).find(10L)
        order.verify(entities).createNativeQuery("SELECT id FROM pawbridge_animal.shelters WHERE id = :id FOR UPDATE")
        order.verify(lock).setParameter("id", 7L)
        order.verify(lock).getResultList()
        order.verify(entities).find(Shelter::class.java, 7L)
        order.verify(animals).getResultList()
        order.verify(members).exists("care-7")
        order.verify(entities).remove(shelter)
    }

    @Test
    fun givenNativeTargetAbsent__whenUpdateOrDelete__thenRejectBeforeManagedLoadOrReferences() {
        doReturn(listOf<Any>()).`when`(lock).getResultList()

        assertThatThrownBy { commands.update(10L, 7L, update) }.isInstanceOf(EntityNotFoundException::class.java)
        assertThatThrownBy { commands.delete(10L, 7L) }.isInstanceOf(EntityNotFoundException::class.java)

        verify(entities, never()).find(Shelter::class.java, 7L)
        verify(entities, never()).remove(any())
        verifyNoInteractions(members, search, animals)
    }

    @Test
    fun givenNativeLockFailure__whenUpdate__thenPropagateAndRollbackBeforeLoadingEntity() {
        doThrow(LockTimeoutException("lock timeout")).`when`(lock).getResultList()

        assertThatThrownBy { commands.update(10L, 7L, update) }.isInstanceOf(LockTimeoutException::class.java)

        verify(entities, never()).find(Shelter::class.java, 7L)
        verify(manager).rollback(status)
        assertThat(shelter.phone).isEqualTo("old phone")
        verifyNoInteractions(search, members, animals)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9])
    fun givenSupplementaryTextInAnOriginalCreateColumn__whenCreatingAfterAdministratorCheck__thenRejectBeforeSqlAndRollback(
        column: Int,
    ) {
        val values = arrayOf(
            create.careRegNo, create.name, create.phone, create.address, create.ownerName, create.organizationName,
            create.email, create.introduction, create.adoptionProcedure, create.operatingHours,
        )
        values[column] = "unsupported-🚀-text"
        val request = ShelterCommands.Create(
            values[0], values[1], values[2], values[3], values[4], values[5], values[6], values[7], values[8], values[9],
        )

        assertThatThrownBy { commands.create(10L, request) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage(PostgresqlRollbackCharsetViolation.MESSAGE)

        verify(actors).find(10L)
        verify(entities, never()).persist(any())
        verify(entities, never()).flush()
        verifyNoInteractions(search, members)
        verify(manager).rollback(status)
        verify(manager, never()).commit(any())
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2, 3, 4])
    fun givenSupplementaryTextInAnOriginalUpdateColumn__whenUpdatingAfterAuthorityAndTargetLock__thenKeepFieldsAndRejectBeforeSql(
        column: Int,
    ) {
        val values = arrayOf(update.phone, update.email, update.introduction, update.adoptionProcedure, update.operatingHours)
        values[column] = "unsupported-🚀-text"
        val previous = shelter.introduction

        assertThatThrownBy {
            commands.update(10L, 7L, ShelterCommands.Update(values[0], values[1], values[2], values[3], values[4]))
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage(PostgresqlRollbackCharsetViolation.MESSAGE)

        verify(actors).find(10L)
        verify(lock).getResultList()
        verify(entities).find(Shelter::class.java, 7L)
        assertThat(shelter.introduction).isEqualTo(previous)
        verify(entities, never()).persist(any())
        verify(entities, never()).flush()
        verifyNoInteractions(search, members)
        verify(manager).rollback(status)
        verify(manager, never()).commit(any())
    }
}
