package com.pawbridge.backend.user.internal.storeorder.infrastructure

import com.pawbridge.backend.store.api.StoreOrderAdministrators.Actor
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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

class JdbcStoreOrderAdministratorsTest {
    private val source: DataSource = mockk<DataSource>()
    private val jdbc: JdbcTemplate = mockk<JdbcTemplate>()
    private val member = MemberRow()
    private val resource = ConnectionHolder(mockk<Connection>())
    private val administrators = JdbcStoreOrderAdministrators(jdbc)

    @BeforeEach
    fun bindSharedWriterTransaction() {
        TransactionSynchronizationManager.clear()
        TransactionSynchronizationManager.setActualTransactionActive(true)
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false)
        TransactionSynchronizationManager.bindResource(source, resource)
    }

    @AfterEach
    fun releaseTheTestBinding() {
        TransactionSynchronizationManager.unbindResourceIfPossible(source)
        TransactionSynchronizationManager.clear()
    }

    @ParameterizedTest
    @CsvSource("true,ROLE_ADMIN", "false,ROLE_ADMIN", "true,ROLE_USER", "false,admin", "false,ROLE_SHELTER")
    fun `데이터베이스 역할이 주어졌을 때__맞는 트랜잭션 안에서 관찰하면__역할을 보존하고 쓰기일 때만 잠근다`(
        write: Boolean,
        role: String,
    ) {
        // given
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(!write)
        member.role = role

        every { jdbc.dataSource } returns source
        every { jdbc.query(any<String>(), any<RowMapper<Any>>(), any<Any>()) } answers {
            mutableListOf(secondArg<RowMapper<Any>>().mapRow(member.resultSet(), 0)!!)
        }
        every { jdbc.queryForObject(any<String>(), Boolean::class.javaObjectType, any<Any>()) } returns false

        // when
        val actor = current(write)

        // then
        assertEquals(Actor(7, true, false, role), actor)
        if (write) {
            verifyOrder {
                jdbc.query(LOCK, any<RowMapper<Any>>(), 7L)
                jdbc.queryForObject(PENDING, Boolean::class.javaObjectType, 7L)
            }
        } else {
            verify(exactly = 1) { jdbc.query(READ, any<RowMapper<Any>>(), 7L) }
            verify(exactly = 0) { jdbc.queryForObject(any<String>(), Boolean::class.javaObjectType, any<Any>()) }
        }
        verify(exactly = 0) { jdbc.query(if (write) READ else LOCK, any<RowMapper<Any>>(), any<Any>()) }
        assertSame(resource, TransactionSynchronizationManager.getResource(source))
        assertEquals(!write, TransactionSynchronizationManager.isCurrentTransactionReadOnly())
        // The strict mock fails on any call; hashCode is only used by the transaction resource map.
        verify(exactly = 0) { source.connection }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `사용자가 없을 때__현재 권한을 읽으면__대기 조회 없이 사용할 수 없음을 돌려준다`(write: Boolean) {
        // given
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(!write)

        every { jdbc.dataSource } returns source
        every { jdbc.query(any<String>(), any<RowMapper<Any>>(), any<Any>()) } returns mutableListOf()

        // when
        val actor = current(write)

        // then
        assertEquals(Actor(7, false, true, null), actor)
        verify(exactly = 0) { jdbc.queryForObject(any<String>(), Boolean::class.javaObjectType, any<Any>()) }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `연락처 삭제가 대기 중일 때__권한을 읽으면__데이터베이스 역할은 유지하되 행위자를 비활성화한다`(write: Boolean) {
        // given
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(!write)
        member.pending = true

        every { jdbc.dataSource } returns source
        every { jdbc.query(any<String>(), any<RowMapper<Any>>(), any<Any>()) } answers {
            mutableListOf(secondArg<RowMapper<Any>>().mapRow(member.resultSet(), 0)!!)
        }
        every { jdbc.queryForObject(any<String>(), Boolean::class.javaObjectType, any<Any>()) } returns true

        // when
        val actor = current(write)

        // then
        assertEquals(Actor(7, false, true, "ROLE_ADMIN"), actor)
        if (write) {
            verifyOrder {
                jdbc.query(LOCK, any<RowMapper<Any>>(), 7L)
                jdbc.queryForObject(PENDING, Boolean::class.javaObjectType, 7L)
            }
        } else {
            verify(exactly = 1) { jdbc.query(READ, any<RowMapper<Any>>(), 7L) }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `트랜잭션 상태가 없거나 잘못됐을 때__권한을 읽으면__사용자 SQL 전에 거부한다`(write: Boolean) {
        // given
        var dataSource: DataSource? = source
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(!write)

        every { jdbc.dataSource } answers { dataSource }

        // when
        TransactionSynchronizationManager.setActualTransactionActive(false)
        assertThrows(IllegalStateException::class.java) { current(write) }
        TransactionSynchronizationManager.setActualTransactionActive(true)
        TransactionSynchronizationManager.unbindResource(source)
        assertThrows(IllegalStateException::class.java) { current(write) }
        TransactionSynchronizationManager.bindResource(source, resource)
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(write)
        assertThrows(IllegalStateException::class.java) { current(write) }
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(!write)
        dataSource = null
        assertThrows(IllegalStateException::class.java) { current(write) }

        // then
        verifyNoSql()
    }

    @Test
    fun `양수가 아닌 검증된 아이디일 때__현재 권한을 요청하면__SQL 전에 거부한다`() {
        // given
        every { jdbc.dataSource } returns source

        // when
        for (id in listOf(0L, -1L)) {
            assertThrows(IllegalArgumentException::class.java) { administrators.currentForRead(id) }
            assertThrows(IllegalArgumentException::class.java) { administrators.currentForWrite(id) }
        }

        // then
        verifyNoSql()
    }

    @ParameterizedTest
    @CsvSource("true,member", "false,member", "true,pending")
    fun `SQL이 실패할 때__권한을 관찰하면__같은 예외를 전파하고 트랜잭션 바인딩을 보존한다`(
        write: Boolean,
        phase: String,
    ) {
        // given
        val failure = DataAccessResourceFailureException("Synthetic authority unavailable")
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(!write)

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
        }
        assertSame(resource, TransactionSynchronizationManager.getResource(source))
    }

    @Test
    fun `대기 중 여부를 관찰할 수 없을 때__쓰기 권한을 읽으면__활성 관리자를 만들어 내지 않는다`() {
        // given
        every { jdbc.dataSource } returns source
        every { jdbc.query(any<String>(), any<RowMapper<Any>>(), any<Any>()) } answers {
            mutableListOf(secondArg<RowMapper<Any>>().mapRow(member.resultSet(), 0)!!)
        }
        every { jdbc.queryForObject(any<String>(), Boolean::class.javaObjectType, any<Any>()) } returns null

        // when
        assertThrows(NullPointerException::class.java) { administrators.currentForWrite(7) }
    }

    private fun current(write: Boolean): Actor? {
        return if (write) administrators.currentForWrite(7) else administrators.currentForRead(7)
    }

    private fun verifyNoSql() {
        verify(exactly = 0) { jdbc.query(any<String>(), any<RowMapper<Any>>(), any<Any>()) }
        verify(exactly = 0) { jdbc.queryForObject(any<String>(), Boolean::class.javaObjectType, any<Any>()) }
    }

    /** In-memory current-user row; the strict mocks do not cover the typed getters used by the row mapper. */
    private class MemberRow {
        var role: String? = "ROLE_ADMIN"
        var pending = false

        fun resultSet(): ResultSet {
            val handler = InvocationHandler { _, method, args ->
                when {
                    method.name == "getLong" && args[0] == "user_id" -> 7L
                    method.name == "getString" && args[0] == "role" -> role
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
        private const val LOCK = "SELECT user_id,role FROM pawbridge_user.users WHERE user_id=? FOR UPDATE"
        private val READ = """
            SELECT u.user_id, u.role, d.user_id IS NOT NULL AS pending
            FROM pawbridge_user.users u
            LEFT JOIN pawbridge_user.contact_deletions d ON d.user_id = u.user_id
            WHERE u.user_id = ?
        """.trimIndent() + "\n"
        private const val PENDING = "SELECT EXISTS(SELECT 1 FROM pawbridge_user.contact_deletions WHERE user_id=?)"
    }
}
