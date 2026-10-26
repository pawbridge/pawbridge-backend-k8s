package com.pawbridge.backend.user.internal.animalcommand.infrastructure

import com.pawbridge.backend.animal.api.AnimalCommandActors.Actor
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.excludeRecords
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Connection
import java.sql.ResultSet
import javax.sql.DataSource

class JdbcAnimalCommandActorsTest {
    private val source: DataSource = mockk<DataSource>()
    private val jdbc: JdbcTemplate = mockk<JdbcTemplate>()
    private val member: ResultSet = mockk<ResultSet>()
    private val resource = ConnectionHolder(mockk<Connection>())
    private val actors = JdbcAnimalCommandActors(jdbc)

    @BeforeEach
    fun bindSharedWriterTransaction() {
        TransactionSynchronizationManager.clear()
        TransactionSynchronizationManager.setActualTransactionActive(true)
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false)
        TransactionSynchronizationManager.bindResource(source, resource)
    }

    @AfterEach
    fun releaseOnlyTheTestTransactionBinding() {
        TransactionSynchronizationManager.unbindResourceIfPossible(source)
        TransactionSynchronizationManager.clear()
    }

    @ParameterizedTest
    @CsvSource(value = ["ROLE_ADMIN,<null>", "ROLE_SHELTER,care-7"], nullValues = ["<null>"])
    fun `현재 역할과 널 허용 보호소 식별자가 있을 때__검증된 주체를 찾으면__잠긴 현재 사용자만 매핑한다`(
        role: String,
        care: String?,
    ) {
        // given
        // The transaction manager keys its resources by the data source, so hashCode is not a data source call.
        excludeRecords { source.hashCode() }

        every { jdbc.dataSource } returns source
        every { member.getLong("user_id") } returns 7L
        every { member.getString("role") } returns role
        every { member.getString("care_reg_no") } returns care
        every { jdbc.query(any<String>(), any<RowMapper<Any>>(), *anyVararg()) } answers {
            listOf(secondArg<RowMapper<*>>().mapRow(member, 0))
        }
        every { jdbc.queryForObject(any<String>(), any<Class<Boolean>>(), *anyVararg()) } returns false

        // when
        val actor = actors.find(7)

        // then
        assertEquals(Actor(7, role, care, true, false), actor)
        verifyOrder {
            jdbc.query(MEMBER, any<RowMapper<Any>>(), 7L)
            jdbc.queryForObject(PENDING, Boolean::class.javaObjectType, 7L)
        }
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
        assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly())
        assertSame(resource, TransactionSynchronizationManager.getResource(source))
        confirmVerified(source)
    }

    @Test
    fun `연락처 삭제가 대기 중일 때__잠긴 사용자를 찾으면__역할과 보호소 번호는 유지하되 비활성 삭제됨으로 돌려준다`() {
        // given
        every { jdbc.dataSource } returns source
        every { member.getLong("user_id") } returns 7L
        every { member.getString("role") } returns "ROLE_SHELTER"
        every { member.getString("care_reg_no") } returns "care-7"
        every { jdbc.query(any<String>(), any<RowMapper<Any>>(), *anyVararg()) } answers {
            listOf(secondArg<RowMapper<*>>().mapRow(member, 0))
        }
        every { jdbc.queryForObject(any<String>(), any<Class<Boolean>>(), *anyVararg()) } returns true

        // when
        val actor = actors.find(7)

        // then
        assertEquals(Actor(7, "ROLE_SHELTER", "care-7", false, true), actor)
        verifyOrder {
            jdbc.query(MEMBER, any<RowMapper<Any>>(), 7L)
            jdbc.queryForObject(PENDING, Boolean::class.javaObjectType, 7L)
        }
    }

    @Test
    fun `현재 사용자가 없을 때__검증된 주체를 찾으면__역할을 지어내거나 대기 상태를 읽지 않고 비활성 삭제됨으로 돌려준다`() {
        // given
        every { jdbc.dataSource } returns source
        every { jdbc.query(any<String>(), any<RowMapper<Any>>(), *anyVararg()) } returns listOf<Any>()

        // when
        val actor = actors.find(7)

        // then
        assertEquals(Actor(7, null, null, false, true), actor)
        verify(exactly = 1) { jdbc.query(MEMBER, any<RowMapper<Any>>(), 7L) }
        verify(exactly = 0) { jdbc.queryForObject(any<String>(), Boolean::class.javaObjectType, *anyVararg()) }
    }

    @Test
    fun `쓰기 트랜잭션이 없거나 다른 데이터 소스 바인딩뿐일 때__찾으면__어떤 SQL보다 먼저 거부한다`() {
        // given
        every { jdbc.dataSource } returns source

        // when
        TransactionSynchronizationManager.setActualTransactionActive(false)
        assertThrows(IllegalStateException::class.java) { actors.find(7) }
        TransactionSynchronizationManager.setActualTransactionActive(true)
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true)
        assertThrows(IllegalStateException::class.java) { actors.find(7) }
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false)
        TransactionSynchronizationManager.unbindResource(source)
        val other = mockk<DataSource>()
        TransactionSynchronizationManager.bindResource(other, resource)
        try {
            assertThrows(IllegalStateException::class.java) { actors.find(7) }
        } finally {
            TransactionSynchronizationManager.unbindResource(other)
        }

        // then
        verify(exactly = 0) { jdbc.query(any<String>(), any<RowMapper<Any>>(), *anyVararg()) }
        verify(exactly = 0) { jdbc.queryForObject(any<String>(), Boolean::class.javaObjectType, *anyVararg()) }
    }

    @Test
    fun `검증된 식별자가 양수가 아닐 때__찾으면__어떤 SQL보다 먼저 거부한다`() {
        // given
        every { jdbc.dataSource } returns source

        // when
        assertAll(
            Executable { assertThrows(IllegalArgumentException::class.java) { actors.find(0) } },
            Executable { assertThrows(IllegalArgumentException::class.java) { actors.find(-1) } },
        )

        // then
        verify(exactly = 0) { jdbc.query(any<String>(), any<RowMapper<Any>>(), *anyVararg()) }
        verify(exactly = 0) { jdbc.queryForObject(any<String>(), Boolean::class.javaObjectType, *anyVararg()) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["member", "pending"])
    fun `잠금이나 대기 삭제 조회에서 SQL 실패가 날 때__찾으면__같은 실패를 전파하고 이어가지 않는다`(phase: String) {
        // given
        val failure = DataAccessResourceFailureException("Synthetic lookup unavailable")

        every { jdbc.dataSource } returns source
        every { member.getLong("user_id") } returns 7L
        every { member.getString("role") } returns "ROLE_ADMIN"
        every { member.getString("care_reg_no") } returns null
        if (phase == "member") {
            every { jdbc.query(any<String>(), any<RowMapper<Any>>(), *anyVararg()) } throws failure
        } else {
            every { jdbc.query(any<String>(), any<RowMapper<Any>>(), *anyVararg()) } answers {
                listOf(secondArg<RowMapper<*>>().mapRow(member, 0))
            }
            every { jdbc.queryForObject(any<String>(), any<Class<Boolean>>(), *anyVararg()) } throws failure
        }

        // when
        val thrown = assertThrows(DataAccessResourceFailureException::class.java) { actors.find(7) }

        // then
        assertSame(failure, thrown)
        if (phase == "member") {
            verify(exactly = 0) { jdbc.queryForObject(any<String>(), Boolean::class.javaObjectType, *anyVararg()) }
        } else {
            verifyOrder {
                jdbc.query(MEMBER, any<RowMapper<Any>>(), 7L)
                jdbc.queryForObject(PENDING, Boolean::class.javaObjectType, 7L)
            }
        }
        assertSame(resource, TransactionSynchronizationManager.getResource(source))
    }

    companion object {
        private const val MEMBER = "SELECT user_id,role,care_reg_no FROM pawbridge_user.users WHERE user_id=? FOR UPDATE"
        private const val PENDING = "SELECT EXISTS(SELECT 1 FROM pawbridge_user.contact_deletions WHERE user_id=?)"
    }
}
