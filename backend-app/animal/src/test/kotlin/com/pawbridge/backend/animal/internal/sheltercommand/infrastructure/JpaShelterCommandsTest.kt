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
import io.mockk.clearMocks
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityNotFoundException
import jakarta.persistence.LockTimeoutException
import jakarta.persistence.Query
import jakarta.persistence.TypedQuery
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.NullAndEmptySource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime
import javax.sql.DataSource

class JpaShelterCommandsTest {
    private val entities: EntityManager = mockk<EntityManager>()
    private val manager: JpaTransactionManager = mockk<JpaTransactionManager>()
    private val status: TransactionStatus = mockk<TransactionStatus>()
    private val search: SearchDocumentWriter = mockk<SearchDocumentWriter>()
    private val actors: AnimalCommandActors = mockk<AnimalCommandActors>()
    private val members: AnimalShelterMembers = mockk<AnimalShelterMembers>()
    private val lock: Query = mockk<Query>()
    private val source: DataSource = mockk<DataSource>()
    private val animals: TypedQuery<Long> = mockk<TypedQuery<Long>>()
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
    private val admin = Actor(10L, "ROLE_ADMIN", null, true, false)
    private val writes = TransactionTemplate(manager).apply {
        isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
    }
    private val create = ShelterCommands.Create(
        "care-new", " 새 보호소 ", "02", "주소", "대표", "기관", "new@example.test", "소개", "절차", "09-18",
    )
    private val update = ShelterCommands.Update("new phone", "new@example.test", "new intro", "new adoption", "new hours")

    /** Builds the commands; the data-source lookups made by the constructor are not part of what tests verify. */
    private fun newCommands(): JpaShelterCommands {
        val commands = JpaShelterCommands(entities, manager, writes, search, actors, members)
        clearMocks(search, answers = false)
        return commands
    }

    @Test
    fun `관리자일 때__생성하면__원래의 모든 필드를 보존하고 같은 트랜잭션의 검색 전에 flush한다`() {
        // given
        val created = LocalDateTime.of(2026, 10, 21, 3, 0)

        every { manager.dataSource } returns source
        every { search.dataSource() } returns source
        every { manager.getTransaction(any()) } returns status
        justRun { manager.commit(any()) }
        every { actors.find(any()) } returns admin
        every { entities.persist(any()) } answers {
            val value: Shelter = arg(0)
            ReflectionTestUtils.setField(value, "id", 9L)
            ReflectionTestUtils.setField(value, "createdAt", created)
            ReflectionTestUtils.setField(value, "updatedAt", created)
        }
        justRun { entities.flush() }
        justRun { search.shelter(any<Long>()) }
        val commands = newCommands()

        // when
        val result = commands.create(10L, create)

        // then
        assertThat(result).isEqualTo(
            ShelterCommands.Details(
                9L, "care-new", " 새 보호소 ", "02", "주소", "대표", "기관",
                "new@example.test", "소개", "절차", "09-18", created, created,
            ),
        )
        verifyOrder {
            actors.find(10L)
            entities.persist(any<Shelter>())
            entities.flush()
            search.shelter(9L)
            manager.commit(status)
        }
        confirmVerified(members)
    }

    @Test
    fun `검색이 실패할 때__생성하면__예외를 전파하고 커밋 없이 쓰기 롤백을 요청한다`() {
        // given
        every { manager.dataSource } returns source
        every { search.dataSource() } returns source
        every { manager.getTransaction(any()) } returns status
        justRun { manager.rollback(any()) }
        every { actors.find(any()) } returns admin
        every { entities.persist(any()) } answers {
            val saved: Shelter = arg(0)
            ReflectionTestUtils.setField(saved, "id", 9L)
        }
        justRun { entities.flush() }
        every { search.shelter(any<Long>()) } throws IllegalStateException("search")
        val commands = newCommands()

        // when
        val thrown = assertThatThrownBy { commands.create(10L, create) }

        // then
        thrown.isInstanceOf(IllegalStateException::class.java)
            .hasMessage("search")
        verify(exactly = 1) { manager.rollback(status) }
        verify(exactly = 0) { manager.commit(any()) }
    }

    @Test
    fun `보호소 소속 회원 본인일 때__수정하면__사용자를 보호소보다 먼저 잠그고 관리되는 다섯 필드만 바꾼다`() {
        // given
        every { manager.dataSource } returns source
        every { search.dataSource() } returns source
        every { manager.getTransaction(any()) } returns status
        justRun { manager.commit(any()) }
        every { actors.find(any()) } returns Actor(10L, "ROLE_SHELTER", "care-7", true, false)
        every { entities.createNativeQuery(any<String>()) } returns lock
        every { lock.setParameter(any<String>(), any()) } returns lock
        every { lock.resultList } returns listOf(7L)
        every { entities.find(Shelter::class.java, any()) } returns shelter
        justRun { entities.flush() }
        val commands = newCommands()

        // when
        val result = commands.update(10L, 7L, update)

        // then
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
        verifyOrder {
            actors.find(10L)
            entities.find(Shelter::class.java, 7L)
            entities.flush()
        }
        confirmVerified(search, members)
    }

    @Test
    fun `다른 보호소 등록번호를 가진 관리자일 때__수정하면__모든 보호소를 허용한다`() {
        // given
        every { manager.dataSource } returns source
        every { search.dataSource() } returns source
        every { manager.getTransaction(any()) } returns status
        justRun { manager.commit(any()) }
        every { actors.find(any()) } returns Actor(10L, "ROLE_ADMIN", "other", true, false)
        every { entities.createNativeQuery(any<String>()) } returns lock
        every { lock.setParameter(any<String>(), any()) } returns lock
        every { lock.resultList } returns listOf(7L)
        every { entities.find(Shelter::class.java, any()) } returns shelter
        justRun { entities.flush() }
        val commands = newCommands()

        // when
        val result = commands.update(10L, 7L, update)

        // then
        assertThat(result.phone).isEqualTo("new phone")
    }

    @Test
    fun `선택 필드를 생략한 요청일 때__수정하면__원래 PUT 계약대로 다섯 필드를 모두 비운다`() {
        // given
        every { manager.dataSource } returns source
        every { search.dataSource() } returns source
        every { manager.getTransaction(any()) } returns status
        justRun { manager.commit(any()) }
        every { actors.find(any()) } returns admin
        every { entities.createNativeQuery(any<String>()) } returns lock
        every { lock.setParameter(any<String>(), any()) } returns lock
        every { lock.resultList } returns listOf(7L)
        every { entities.find(Shelter::class.java, any()) } returns shelter
        justRun { entities.flush() }
        val commands = newCommands()

        // when
        val result = commands.update(10L, 7L, ShelterCommands.Update(null, null, null, null, null))

        // then
        assertThat(result.phone).isNull()
        assertThat(result.email).isNull()
        assertThat(result.introduction).isNull()
        assertThat(result.adoptionProcedure).isNull()
        assertThat(result.operatingHours).isNull()
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = [" ", "care-8", "CARE-7"])
    fun `다른 보호소이거나 소속이 없는 회원일 때__수정하면__보호소를 바꾸지 않고 거부한다`(care: String?) {
        // given
        every { manager.dataSource } returns source
        every { search.dataSource() } returns source
        every { manager.getTransaction(any()) } returns status
        justRun { manager.rollback(any()) }
        every { actors.find(any()) } returns Actor(10L, "ROLE_SHELTER", care, true, false)
        every { entities.createNativeQuery(any<String>()) } returns lock
        every { lock.setParameter(any<String>(), any()) } returns lock
        every { lock.resultList } returns listOf(7L)
        every { entities.find(Shelter::class.java, any()) } returns shelter
        val commands = newCommands()

        // when
        val thrown = assertThatThrownBy { commands.update(10L, 7L, update) }

        // then
        thrown.isInstanceOf(ShelterCommandForbiddenException::class.java)
        assertThat(shelter.phone).isEqualTo("old phone")
        confirmVerified(search, members)
    }

    @ParameterizedTest
    @ValueSource(strings = ["ROLE_USER", "ROLE_SUPER_ADMIN", "ADMIN", "SHELTER"])
    fun `승인되지 않은 역할일 때__어떤 명령이든 실행하면__보호소 조회 전에 거부한다`(role: String) {
        // given
        every { manager.dataSource } returns source
        every { search.dataSource() } returns source
        every { manager.getTransaction(any()) } returns status
        justRun { manager.rollback(any()) }
        every { actors.find(any()) } returns Actor(10L, role, "care-7", true, false)
        val commands = newCommands()

        // when
        val created = assertThatThrownBy { commands.create(10L, create) }
        val updated = assertThatThrownBy { commands.update(10L, 7L, update) }
        val deleted = assertThatThrownBy { commands.delete(10L, 7L) }

        // then
        created.isInstanceOf(ShelterCommandForbiddenException::class.java)
        updated.isInstanceOf(ShelterCommandForbiddenException::class.java)
        deleted.isInstanceOf(ShelterCommandForbiddenException::class.java)
        confirmVerified(entities, search, members)
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "inactive", "pending", "wrong-id"])
    fun `현재 계정이 유효하지 않을 때__수정하면__보호소 잠금 전에 거부한다`(kind: String) {
        // given
        val actor = if (kind == "missing") {
            null
        } else {
            Actor(
                if (kind == "wrong-id") 11L else 10L,
                "ROLE_ADMIN",
                null,
                kind != "inactive",
                kind == "pending",
            )
        }

        every { manager.dataSource } returns source
        every { search.dataSource() } returns source
        every { manager.getTransaction(any()) } returns status
        justRun { manager.rollback(any()) }
        every { actors.find(any()) } returns actor
        val commands = newCommands()

        // when
        val thrown = assertThatThrownBy { commands.update(10L, 7L, update) }

        // then
        thrown.isInstanceOf(ShelterCommandForbiddenException::class.java)
        confirmVerified(entities, search, members)
    }

    @Test
    fun `신원이 검증되지 않았을 때__어떤 명령이든 실행하면__사용자나 보호소를 조회하지 않는다`() {
        // given
        every { manager.dataSource } returns source
        every { search.dataSource() } returns source
        every { manager.getTransaction(any()) } returns status
        justRun { manager.rollback(any()) }
        val commands = newCommands()

        // when
        val created = assertThatThrownBy { commands.create(null, create) }
        val updated = assertThatThrownBy { commands.update(0L, 7L, update) }
        val deleted = assertThatThrownBy { commands.delete(-1L, 7L) }

        // then
        created.isInstanceOf(ShelterCommandForbiddenException::class.java)
        updated.isInstanceOf(ShelterCommandForbiddenException::class.java)
        deleted.isInstanceOf(ShelterCommandForbiddenException::class.java)
        confirmVerified(actors, entities, search, members)
    }

    @Test
    fun `보호소 역할일 때__생성이나 삭제를 하면__대상 조회 전에 거부한다`() {
        // given
        every { manager.dataSource } returns source
        every { search.dataSource() } returns source
        every { manager.getTransaction(any()) } returns status
        justRun { manager.rollback(any()) }
        every { actors.find(any()) } returns Actor(10L, "ROLE_SHELTER", "care-7", true, false)
        val commands = newCommands()

        // when
        val created = assertThatThrownBy { commands.create(10L, create) }
        val deleted = assertThatThrownBy { commands.delete(10L, 7L) }

        // then
        created.isInstanceOf(ShelterCommandForbiddenException::class.java)
        deleted.isInstanceOf(ShelterCommandForbiddenException::class.java)
        confirmVerified(entities, search, members)
    }

    @Test
    fun `연결된 동물이 있을 때__삭제하면__삭제하거나 회원을 확인하지 않고 충돌로 거부한다`() {
        // given
        every { manager.dataSource } returns source
        every { search.dataSource() } returns source
        every { manager.getTransaction(any()) } returns status
        justRun { manager.rollback(any()) }
        every { actors.find(any()) } returns admin
        every { entities.createNativeQuery(any<String>()) } returns lock
        every { lock.setParameter(any<String>(), any()) } returns lock
        every { lock.resultList } returns listOf(7L)
        every { entities.find(Shelter::class.java, any()) } returns shelter
        every { entities.createQuery(any<String>(), Long::class.javaObjectType) } returns animals
        every { animals.setParameter(any<String>(), any()) } returns animals
        every { animals.setMaxResults(any()) } returns animals
        every { animals.resultList } returns listOf()
        every { animals.resultList } returns listOf(3L)
        val commands = newCommands()

        // when
        val thrown = assertThatThrownBy { commands.delete(10L, 7L) }

        // then
        thrown.isInstanceOf(ShelterCommandConflictException::class.java)
        verify(exactly = 1) { animals.setMaxResults(1) }
        verify(exactly = 0) { entities.remove(any()) }
        confirmVerified(members, search)
    }

    @Test
    fun `연결된 회원이 있을 때__삭제하면__보호소 잠금 뒤에 읽고 삭제 없이 충돌로 거부한다`() {
        // given
        every { manager.dataSource } returns source
        every { search.dataSource() } returns source
        every { manager.getTransaction(any()) } returns status
        justRun { manager.rollback(any()) }
        every { actors.find(any()) } returns admin
        every { entities.createNativeQuery(any<String>()) } returns lock
        every { lock.setParameter(any<String>(), any()) } returns lock
        every { lock.resultList } returns listOf(7L)
        every { entities.find(Shelter::class.java, any()) } returns shelter
        every { entities.createQuery(any<String>(), Long::class.javaObjectType) } returns animals
        every { animals.setParameter(any<String>(), any()) } returns animals
        every { animals.setMaxResults(any()) } returns animals
        every { animals.resultList } returns listOf()
        every { members.exists(any()) } returns true
        val commands = newCommands()

        // when
        val thrown = assertThatThrownBy { commands.delete(10L, 7L) }

        // then
        thrown.isInstanceOf(ShelterCommandConflictException::class.java)
        verifyOrder {
            actors.find(10L)
            entities.find(Shelter::class.java, 7L)
            animals.resultList
            members.exists("care-7")
        }
        verify(exactly = 0) { entities.remove(any()) }
        confirmVerified(search)
    }

    @Test
    fun `참조가 없을 때__삭제하면__잠근 보호소만 지우고 커밋한다`() {
        // given
        every { manager.dataSource } returns source
        every { search.dataSource() } returns source
        every { manager.getTransaction(any()) } returns status
        justRun { manager.commit(any()) }
        every { actors.find(any()) } returns admin
        every { entities.createNativeQuery(any<String>()) } returns lock
        every { lock.setParameter(any<String>(), any()) } returns lock
        every { lock.resultList } returns listOf(7L)
        every { entities.find(Shelter::class.java, any()) } returns shelter
        every { entities.createQuery(any<String>(), Long::class.javaObjectType) } returns animals
        every { animals.setParameter(any<String>(), any()) } returns animals
        every { animals.setMaxResults(any()) } returns animals
        every { animals.resultList } returns listOf()
        every { members.exists(any()) } returns false
        justRun { entities.remove(any()) }
        val commands = newCommands()

        // when
        commands.delete(10L, 7L)

        // then
        verifyOrder {
            actors.find(10L)
            entities.find(Shelter::class.java, 7L)
            members.exists("care-7")
            entities.remove(shelter)
            manager.commit(status)
        }
        confirmVerified(search)
    }

    @Test
    fun `보호소가 없을 때__수정이나 삭제를 하면__원래의 JPA 없음 예외를 유지한다`() {
        // given
        every { manager.dataSource } returns source
        every { search.dataSource() } returns source
        every { manager.getTransaction(any()) } returns status
        justRun { manager.rollback(any()) }
        every { actors.find(any()) } returns admin
        every { entities.createNativeQuery(any<String>()) } returns lock
        every { lock.setParameter(any<String>(), any()) } returns lock
        every { lock.resultList } returns listOf(7L)
        every { entities.find(Shelter::class.java, any()) } returns shelter
        every { entities.find(Shelter::class.java, any()) } returns null
        val commands = newCommands()

        // when
        val updated = assertThatThrownBy { commands.update(10L, 7L, update) }
        val deleted = assertThatThrownBy { commands.delete(10L, 7L) }

        // then
        updated.isInstanceOf(EntityNotFoundException::class.java)
        deleted.isInstanceOf(EntityNotFoundException::class.java)
        confirmVerified(members, search)
    }

    @ParameterizedTest
    @ValueSource(strings = ["read-only", "repeatable", "default-isolation", "requires-new", "other-manager"])
    fun `호환되지 않는 트랜잭션 템플릿일 때__생성하면__거부한다`(kind: String) {
        // given
        every { manager.dataSource } returns source
        every { search.dataSource() } returns source

        when (kind) {
            "read-only" -> writes.isReadOnly = true
            "repeatable" -> writes.isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
            "default-isolation" -> writes.isolationLevel = TransactionDefinition.ISOLATION_DEFAULT
            "requires-new" -> writes.propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
            "other-manager" -> writes.transactionManager = mockk<JpaTransactionManager>()
        }

        // when
        val thrown = assertThatThrownBy { JpaShelterCommands(entities, manager, writes, search, actors, members) }

        // then
        thrown.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `현재 관리자일 때__삭제하면__엔티티나 참조를 읽기 전에 네이티브 FOR UPDATE로 승인을 배제한다`() {
        // given
        every { manager.dataSource } returns source
        every { search.dataSource() } returns source
        every { manager.getTransaction(any()) } returns status
        justRun { manager.commit(any()) }
        every { actors.find(any()) } returns admin
        every { entities.createNativeQuery(any<String>()) } returns lock
        every { lock.setParameter(any<String>(), any()) } returns lock
        every { lock.resultList } returns listOf(7L)
        every { entities.find(Shelter::class.java, any()) } returns shelter
        every { entities.createQuery(any<String>(), Long::class.javaObjectType) } returns animals
        every { animals.setParameter(any<String>(), any()) } returns animals
        every { animals.setMaxResults(any()) } returns animals
        every { animals.resultList } returns listOf()
        every { members.exists(any()) } returns false
        justRun { entities.remove(any()) }
        val commands = newCommands()

        // when
        commands.delete(10L, 7L)

        // then
        verifyOrder {
            actors.find(10L)
            entities.createNativeQuery("SELECT id FROM pawbridge_animal.shelters WHERE id = :id FOR UPDATE")
            lock.setParameter("id", 7L)
            lock.resultList
            entities.find(Shelter::class.java, 7L)
            animals.resultList
            members.exists("care-7")
            entities.remove(shelter)
        }
    }

    @Test
    fun `네이티브 대상이 없을 때__수정이나 삭제를 하면__관리 엔티티 로드나 참조 확인 전에 거부한다`() {
        // given
        every { manager.dataSource } returns source
        every { search.dataSource() } returns source
        every { manager.getTransaction(any()) } returns status
        justRun { manager.rollback(any()) }
        every { actors.find(any()) } returns admin
        every { entities.createNativeQuery(any<String>()) } returns lock
        every { lock.setParameter(any<String>(), any()) } returns lock
        every { lock.resultList } returns listOf(7L)
        every { entities.find(Shelter::class.java, any()) } returns shelter
        every { lock.resultList } returns listOf<Any>()
        val commands = newCommands()

        // when
        val updated = assertThatThrownBy { commands.update(10L, 7L, update) }
        val deleted = assertThatThrownBy { commands.delete(10L, 7L) }

        // then
        updated.isInstanceOf(EntityNotFoundException::class.java)
        deleted.isInstanceOf(EntityNotFoundException::class.java)
        verify(exactly = 0) { entities.find(Shelter::class.java, 7L) }
        verify(exactly = 0) { entities.remove(any()) }
        confirmVerified(members, search, animals)
    }

    @Test
    fun `네이티브 잠금이 실패할 때__수정하면__예외를 전파하고 엔티티를 로드하기 전에 롤백한다`() {
        // given
        every { manager.dataSource } returns source
        every { search.dataSource() } returns source
        every { manager.getTransaction(any()) } returns status
        justRun { manager.rollback(any()) }
        every { actors.find(any()) } returns admin
        every { entities.createNativeQuery(any<String>()) } returns lock
        every { lock.setParameter(any<String>(), any()) } returns lock
        every { lock.resultList } returns listOf(7L)
        every { entities.find(Shelter::class.java, any()) } returns shelter
        every { lock.resultList } throws LockTimeoutException("lock timeout")
        val commands = newCommands()

        // when
        val thrown = assertThatThrownBy { commands.update(10L, 7L, update) }

        // then
        thrown.isInstanceOf(LockTimeoutException::class.java)
        verify(exactly = 0) { entities.find(Shelter::class.java, 7L) }
        verify(exactly = 1) { manager.rollback(status) }
        assertThat(shelter.phone).isEqualTo("old phone")
        confirmVerified(search, members, animals)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9])
    fun `생성의 원래 컬럼에 보충 문자가 있을 때__관리자 확인 뒤 생성하면__SQL 전에 거부하고 롤백한다`(
        column: Int,
    ) {
        // given
        val values = arrayOf(
            create.careRegNo, create.name, create.phone, create.address, create.ownerName, create.organizationName,
            create.email, create.introduction, create.adoptionProcedure, create.operatingHours,
        )
        values[column] = "unsupported-🚀-text"
        val request = ShelterCommands.Create(
            values[0], values[1], values[2], values[3], values[4], values[5], values[6], values[7], values[8], values[9],
        )

        every { manager.dataSource } returns source
        every { search.dataSource() } returns source
        every { manager.getTransaction(any()) } returns status
        justRun { manager.rollback(any()) }
        every { actors.find(any()) } returns admin
        val commands = newCommands()

        // when
        val thrown = assertThatThrownBy { commands.create(10L, request) }

        // then
        thrown.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage(PostgresqlRollbackCharsetViolation.MESSAGE)
        verify(exactly = 1) { actors.find(10L) }
        verify(exactly = 0) { entities.persist(any()) }
        verify(exactly = 0) { entities.flush() }
        confirmVerified(search, members)
        verify(exactly = 1) { manager.rollback(status) }
        verify(exactly = 0) { manager.commit(any()) }
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2, 3, 4])
    fun `수정의 원래 컬럼에 보충 문자가 있을 때__권한과 대상 잠금 뒤 수정하면__필드를 유지하고 SQL 전에 거부한다`(
        column: Int,
    ) {
        // given
        val values = arrayOf(update.phone, update.email, update.introduction, update.adoptionProcedure, update.operatingHours)
        values[column] = "unsupported-🚀-text"
        val previous = shelter.introduction

        every { manager.dataSource } returns source
        every { search.dataSource() } returns source
        every { manager.getTransaction(any()) } returns status
        justRun { manager.rollback(any()) }
        every { actors.find(any()) } returns admin
        every { entities.createNativeQuery(any<String>()) } returns lock
        every { lock.setParameter(any<String>(), any()) } returns lock
        every { lock.resultList } returns listOf(7L)
        every { entities.find(Shelter::class.java, any()) } returns shelter
        val commands = newCommands()

        // when
        val thrown = assertThatThrownBy {
            commands.update(10L, 7L, ShelterCommands.Update(values[0], values[1], values[2], values[3], values[4]))
        }

        // then
        thrown.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage(PostgresqlRollbackCharsetViolation.MESSAGE)
        verify(exactly = 1) { actors.find(10L) }
        verify(exactly = 1) { lock.resultList }
        verify(exactly = 1) { entities.find(Shelter::class.java, 7L) }
        assertThat(shelter.introduction).isEqualTo(previous)
        verify(exactly = 0) { entities.persist(any()) }
        verify(exactly = 0) { entities.flush() }
        confirmVerified(search, members)
        verify(exactly = 1) { manager.rollback(status) }
        verify(exactly = 0) { manager.commit(any()) }
    }
}
