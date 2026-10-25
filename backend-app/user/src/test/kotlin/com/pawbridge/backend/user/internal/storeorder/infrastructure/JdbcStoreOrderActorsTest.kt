package com.pawbridge.backend.user.internal.storeorder.infrastructure

import com.pawbridge.backend.store.api.StoreOrderActors.Actor
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
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
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.ResultSet
import javax.sql.DataSource

class JdbcStoreOrderActorsTest {
    private val source: DataSource = mockk<DataSource>()
    private val jdbc: JdbcTemplate = mockk<JdbcTemplate>()
    private val member = MemberRow()
    private val resource = ConnectionHolder(mockk<Connection>())
    private val actors = JdbcStoreOrderActors(jdbc)

    @BeforeEach
    fun bindSharedWriterTransaction() {
        TransactionSynchronizationManager.clear()
        TransactionSynchronizationManager.setActualTransactionActive(true)
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false)
        TransactionSynchronizationManager.bindResource(source, resource)
    }

    @AfterEach
    fun releaseOnlyTheTestBinding() {
        TransactionSynchronizationManager.unbindResourceIfPossible(source)
        TransactionSynchronizationManager.clear()
    }

    @ParameterizedTest
    @ValueSource(strings = ["write", "readOnly", "readWrite"])
    fun `현재 회원이 있고 호출자 트랜잭션이 있을 때__상태를 읽으면__쓰기일 때만 잠그고 읽기 상태를 한 문장으로 관찰한다`(
        mode: String,
    ) {
        // given
        val write = mode == "write"
        val readOnly = mode == "readOnly"
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(readOnly)

        every { jdbc.dataSource } returns source
        every { jdbc.query(any<String>(), any<RowMapper<Any>>(), any<Any>()) } answers {
            mutableListOf(secondArg<RowMapper<Any>>().mapRow(member.resultSet(), 0)!!)
        }
        every { jdbc.queryForObject(any<String>(), Boolean::class.javaObjectType, any<Any>()) } returns false

        // when
        val actor = current(write)

        // then
        assertEquals(Actor(7, true, false), actor)
        if (write) {
            verifyOrder {
                jdbc.query(LOCK_MEMBER, any<RowMapper<Any>>(), 7L)
                jdbc.queryForObject(PENDING, Boolean::class.javaObjectType, 7L)
            }
        } else {
            verify(exactly = 1) { jdbc.query(READ_MEMBER, any<RowMapper<Any>>(), 7L) }
            verify(exactly = 0) { jdbc.queryForObject(any<String>(), Boolean::class.javaObjectType, any<Any>()) }
        }
        verify(exactly = 0) { jdbc.query(if (write) READ_MEMBER else LOCK_MEMBER, any<RowMapper<Any>>(), any<Any>()) }
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
        assertEquals(readOnly, TransactionSynchronizationManager.isCurrentTransactionReadOnly())
        assertSame(resource, TransactionSynchronizationManager.getResource(source))
        // The strict mock fails on any call; hashCode is only used by the transaction resource map.
        verify(exactly = 0) { source.connection }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `현재 사용자가 없을 때__쓰기나 읽기로 읽으면__대기 조회 없이 비활성 삭제됨을 돌려준다`(write: Boolean) {
        // given
        every { jdbc.dataSource } returns source
        every { jdbc.query(any<String>(), any<RowMapper<Any>>(), any<Any>()) } returns mutableListOf()

        // when
        val actor = current(write)

        // then
        assertEquals(Actor(7, false, true), actor)
        verify(exactly = 1) { jdbc.query(if (write) LOCK_MEMBER else READ_MEMBER, any<RowMapper<Any>>(), 7L) }
        verify(exactly = 0) { jdbc.queryForObject(any<String>(), Boolean::class.javaObjectType, any<Any>()) }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `연락처 삭제가 대기 중일 때__쓰기나 읽기로 읽으면__회원 조회 뒤에 비활성 삭제됨을 돌려준다`(write: Boolean) {
        // given
        member.pending = true

        every { jdbc.dataSource } returns source
        every { jdbc.query(any<String>(), any<RowMapper<Any>>(), any<Any>()) } answers {
            mutableListOf(secondArg<RowMapper<Any>>().mapRow(member.resultSet(), 0)!!)
        }
        every { jdbc.queryForObject(any<String>(), Boolean::class.javaObjectType, any<Any>()) } returns true

        // when
        val actor = current(write)

        // then
        assertEquals(Actor(7, false, true), actor)
        if (write) {
            verifyOrder {
                jdbc.query(LOCK_MEMBER, any<RowMapper<Any>>(), 7L)
                jdbc.queryForObject(PENDING, Boolean::class.javaObjectType, 7L)
            }
        } else {
            verify(exactly = 1) { jdbc.query(READ_MEMBER, any<RowMapper<Any>>(), 7L) }
            verify(exactly = 0) { jdbc.queryForObject(any<String>(), Boolean::class.javaObjectType, any<Any>()) }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `실제 트랜잭션이 없거나 데이터소스 바인딩이 맞지 않을 때__어느 모드로 읽든__SQL 전에 거부한다`(write: Boolean) {
        // given
        val other = mockk<DataSource>()
        var dataSource: DataSource? = source

        every { jdbc.dataSource } answers { dataSource }

        // when
        TransactionSynchronizationManager.setActualTransactionActive(false)
        assertThrows(IllegalStateException::class.java) { current(write) }
        TransactionSynchronizationManager.setActualTransactionActive(true)
        TransactionSynchronizationManager.unbindResource(source)
        TransactionSynchronizationManager.bindResource(other, resource)
        try {
            assertThrows(IllegalStateException::class.java) { current(write) }
        } finally {
            TransactionSynchronizationManager.unbindResource(other)
        }
        TransactionSynchronizationManager.bindResource(source, resource)
        dataSource = null
        assertThrows(IllegalStateException::class.java) { current(write) }

        // then
        verifyNoMemberSql()
    }

    @Test
    fun `바인딩된 트랜잭션이 읽기 전용일 때__쓰기 상태를 요청하면__사용자 행 잠금을 잡기 전에 거부한다`() {
        // given
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true)

        every { jdbc.dataSource } returns source

        // when
        assertThrows(IllegalStateException::class.java) { actors.currentForWrite(7) }

        // then
        verifyNoMemberSql()
    }

    @Test
    fun `양수가 아닌 검증된 신원일 때__어느 모드로 읽든__SQL 전에 거부한다`() {
        // given
        every { jdbc.dataSource } returns source

        // when
        assertAll(
            Executable { assertThrows(IllegalArgumentException::class.java) { actors.currentForWrite(0) } },
            Executable { assertThrows(IllegalArgumentException::class.java) { actors.currentForWrite(-1) } },
            Executable { assertThrows(IllegalArgumentException::class.java) { actors.currentForRead(0) } },
            Executable { assertThrows(IllegalArgumentException::class.java) { actors.currentForRead(-1) } },
        )

        // then
        verifyNoMemberSql()
    }

    @ParameterizedTest
    @CsvSource("true,member", "false,member", "true,pending")
    fun `SQL이 실패할 때__회원이나 대기 상태를 읽으면__추가 작업 없이 같은 예외를 전파한다`(
        write: Boolean,
        phase: String,
    ) {
        // given
        val failure = DataAccessResourceFailureException("Synthetic current member unavailable")

        every { jdbc.dataSource } returns source
        if (phase == "member") {
            every { jdbc.query(any<String>(), any<RowMapper<Any>>(), any<Any>()) } throws failure
        } else {
            every { jdbc.query(any<String>(), any<RowMapper<Any>>(), any<Any>()) } answers {
                mutableListOf(secondArg<RowMapper<Any>>().mapRow(member.resultSet(), 0)!!)
            }
            every { jdbc.queryForObject(any<String>(), Boolean::class.javaObjectType, any<Any>()) } throws failure
        }

        // when
        val thrown = assertThrows(DataAccessResourceFailureException::class.java) { current(write) }

        // then
        assertSame(failure, thrown)
        if (phase == "member") {
            verify(exactly = 0) { jdbc.queryForObject(any<String>(), Boolean::class.javaObjectType, any<Any>()) }
        } else {
            verifyOrder {
                jdbc.query(LOCK_MEMBER, any<RowMapper<Any>>(), 7L)
                jdbc.queryForObject(PENDING, Boolean::class.javaObjectType, 7L)
            }
        }
        assertSame(resource, TransactionSynchronizationManager.getResource(source))
    }

    private fun current(write: Boolean): Actor? {
        return if (write) actors.currentForWrite(7) else actors.currentForRead(7)
    }

    private fun verifyNoMemberSql() {
        verify(exactly = 0) { jdbc.query(any<String>(), any<RowMapper<Any>>(), any<Any>()) }
        verify(exactly = 0) { jdbc.queryForObject(any<String>(), Boolean::class.javaObjectType, any<Any>()) }
    }

    /** In-memory current-member row; the strict mocks do not cover the typed getters used by the row mapper. */
    private class MemberRow {
        var pending = false

        fun resultSet(): ResultSet {
            val handler = InvocationHandler { _, method, args ->
                when {
                    method.name == "getLong" && args[0] == "user_id" -> 7L
                    method.name == "getBoolean" && args[0] == "pending" -> pending
                    else -> throw AssertionError("Unexpected ResultSet call: ${method.name}")
                }
            }
            return Proxy.newProxyInstance(
                ResultSet::class.java.classLoader,
                arrayOf<Class<*>>(ResultSet::class.java),
                handler,
            ) as ResultSet
        }
    }

    companion object {
        private const val MEMBER = "SELECT user_id FROM pawbridge_user.users WHERE user_id=?"
        private const val LOCK_MEMBER = MEMBER + " FOR UPDATE"
        private val READ_MEMBER = """
            SELECT u.user_id, d.user_id IS NOT NULL AS pending
            FROM pawbridge_user.users u
            LEFT JOIN pawbridge_user.contact_deletions d ON d.user_id = u.user_id
            WHERE u.user_id = ?
        """.trimIndent() + "\n"
        private const val PENDING = "SELECT EXISTS(SELECT 1 FROM pawbridge_user.contact_deletions WHERE user_id=?)"
    }
}
