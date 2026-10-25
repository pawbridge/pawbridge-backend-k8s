package com.pawbridge.backend.user.internal.adminupdate.infrastructure

import com.pawbridge.backend.user.internal.adminupdate.application.AdminUserUpdate.Changes
import com.pawbridge.backend.user.internal.adminupdate.application.AdminUserUpdate.Role
import com.pawbridge.backend.user.internal.adminupdate.application.ShelterApprovalRequiredException
import com.pawbridge.backend.user.internal.nickname.application.NicknameDuplicateException
import com.pawbridge.backend.user.internal.profile.application.UserNotFoundException
import com.pawbridge.backend.user.internal.session.application.UserSessionRevoker
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.UncategorizedSQLException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementCreator
import org.springframework.jdbc.core.ResultSetExtractor
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.lang.reflect.Proxy
import java.sql.Timestamp
import java.time.LocalDateTime
import java.util.Calendar
import java.util.function.Supplier
import java.util.stream.Stream

private fun normalized(sql: String): String {
    return sql.replace(Regex("\\s+"), " ").trim()
}

class JdbcAdminUserUpdateTest {
    @ParameterizedTest
    @ValueSource(longs = [-1, 0, 8])
    fun `대상 사용자가 없고 ID가 양수가 아닐 때__잠금 조회를 하면__새 양수 검사 없이 없는 사용자 예외를 유지한다`(
        id: Long,
    ) {
        // given
        val f = Fixture()
        f.jdbc.present = false

        // when
        assertThrows(UserNotFoundException::class.java) {
            f.query.update(id, Changes("Duplicate", Role.ROLE_SHELTER, "B"))
        }

        // then
        assertEquals(listOf("lock"), f.jdbc.events)
        assertArrayEquals(arrayOf<Any?>(id), f.jdbc.lockArguments)
        assertTrue(f.jdbc.lockSql!!.endsWith("WHERE user_id = ? FOR UPDATE"))
        assertEquals(1, f.tx.rollbacks)
        assertEquals(0, f.samples)
        assertFalse(f.tx.active)
    }

    @ParameterizedTest
    @MethodSource("approvalRequests")
    fun `중복 닉네임보다 먼저 승인이 필요할 때__잠긴 사용자를 수정하면__중복 확인이나 쓰기 전에 거부한다`(
        current: Role,
        care: String?,
        request: Changes,
    ) {
        // given
        val f = Fixture()
        f.jdbc.role = current
        f.jdbc.care = care
        f.jdbc.duplicate = true

        // when
        assertThrows(ShelterApprovalRequiredException::class.java) { f.query.update(8, request) }

        // then
        assertEquals(listOf("lock"), f.jdbc.events)
        assertEquals(0, f.samples)
        assertEquals(1, f.tx.rollbacks)
    }

    @Test
    fun `대소문자만 다른 닉네임이 본인과 일치하며 이미 있을 때__수정하면__원래의 중복 조회와 409 예외를 유지한다`() {
        // given
        val f = Fixture()
        f.jdbc.duplicate = true

        // when
        assertThrows(NicknameDuplicateException::class.java) { f.query.update(8, Changes("ALPHA", null, null)) }

        // then
        assertEquals(listOf("lock", "duplicate"), f.jdbc.events)
        assertEquals("SELECT (count(*) > 0) FROM pawbridge_user.users WHERE lower(nickname) = lower(?)", f.jdbc.duplicateSql)
        assertArrayEquals(arrayOf<Any?>("ALPHA"), f.jdbc.duplicateArguments)
        assertEquals(0, f.samples)
        assertEquals(1, f.tx.rollbacks)
    }

    @ParameterizedTest
    @MethodSource("noChanges")
    fun `변경된 속성이 없을 때__동등한 값을 저장하면__수정과 감사 기록 없이 커밋한다`(request: Changes) {
        // given
        val f = Fixture()
        f.jdbc.care = "A"

        // when
        f.query.update(8, request)

        // then
        assertEquals(listOf("lock"), f.jdbc.events)
        assertEquals(0, f.samples)
        assertEquals(1, f.tx.commits)
        assertEquals(0, f.tx.rollbacks)
        assertFalse(f.tx.active)
    }

    @ParameterizedTest
    @MethodSource("validChanges")
    fun `허용된 변경 필드가 있을 때__수정하면__바꿀 수 있는 필드와 하나의 UTC 감사 시각만 잠금 트랜잭션 안에서 바인딩한다`(
        current: Role,
        currentCare: String?,
        request: Changes,
        nickname: String,
        nextRole: Role,
    ) {
        // given
        val f = Fixture()
        f.jdbc.role = current
        f.jdbc.care = currentCare

        every { f.connection.prepareStatement(any()) } returns f.statement
        justRun { f.statement.setString(any(), any()) }
        justRun { f.statement.setTimestamp(any(), any(), any()) }
        justRun { f.statement.setLong(any(), any()) }

        // when
        f.query.update(8, request)

        // then
        val sql = slot<String>()
        val zone = slot<Calendar>()
        assertEquals(
            if (request.nickname != null && request.nickname != "Alpha") {
                listOf("lock", "duplicate", "update")
            } else {
                listOf("lock", "update")
            },
            f.jdbc.events,
        )
        verify(exactly = 1) { f.connection.prepareStatement(capture(sql)) }
        assertEquals(
            "UPDATE pawbridge_user.users SET nickname = ?, role = ?, care_reg_no = ?, updated_at = ? WHERE user_id = ?",
            normalized(sql.captured),
        )
        verify(exactly = 1) { f.statement.setString(1, nickname) }
        verify(exactly = 1) { f.statement.setString(2, nextRole.name) }
        verify(exactly = 1) { f.statement.setString(3, currentCare) }
        verify(exactly = 1) { f.statement.setTimestamp(4, Timestamp.valueOf(MODIFIED), capture(zone)) }
        assertEquals("UTC", zone.captured.timeZone.id)
        verify(exactly = 1) { f.statement.setLong(5, 8) }
        assertEquals(1, f.samples)
        assertEquals(1, f.tx.starts)
        assertEquals(1, f.tx.commits)
        assertFalse(f.tx.active)
    }

    @ParameterizedTest
    @ValueSource(strings = ["begin", "update", "commit"])
    fun `시작이나 유일성 경합이나 커밋이 실패할 때__수정하면__중복으로 재분류하거나 재시도하지 않고 전파한다`(
        phase: String,
    ) {
        // given
        val f = Fixture()
        val failure: RuntimeException = if (phase == "update") {
            DataIntegrityViolationException("synthetic unique race")
        } else {
            TransactionSystemException("synthetic boundary")
        }
        if (phase == "begin") {
            f.tx.beginFailure = failure
        } else if (phase == "commit") {
            f.tx.commitFailure = failure
        } else {
            f.jdbc.updateFailure = failure
        }

        every { f.connection.prepareStatement(any()) } returns f.statement
        justRun { f.statement.setString(any(), any()) }
        justRun { f.statement.setTimestamp(any(), any(), any()) }
        justRun { f.statement.setLong(any(), any()) }

        // when
        val thrown = assertThrows(RuntimeException::class.java) { f.query.update(8, Changes("Beta", null, null)) }

        // then
        assertSame(failure, thrown)
        assertEquals(1, f.tx.starts)
        assertEquals(if (phase == "update") 1 else 0, f.tx.rollbacks)
        assertFalse(f.tx.active)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 2])
    fun `쓰기 건수가 예상과 다를 때__잠긴 사용자를 수정하면__성공으로 보고하지 않고 롤백한다`(changed: Int) {
        // given
        val f = Fixture()
        f.jdbc.changed = changed

        every { f.connection.prepareStatement(any()) } returns f.statement
        justRun { f.statement.setString(any(), any()) }
        justRun { f.statement.setTimestamp(any(), any(), any()) }
        justRun { f.statement.setLong(any(), any()) }

        // when
        assertThrows(IllegalStateException::class.java) { f.query.update(8, Changes("Beta", null, null)) }

        // then
        assertEquals(1, f.tx.rollbacks)
        assertEquals(0, f.tx.commits)
    }

    @Test
    fun `준비된 문장 바인딩이 실패할 때__수정하면__문장을 닫고 SQL 실패를 전파한다`() {
        // given
        val f = Fixture()
        val failure = SQLException("synthetic bind")

        every { f.connection.prepareStatement(any()) } returns f.statement
        justRun { f.statement.setString(any(), any()) }
        every { f.statement.setString(2, "ROLE_USER") } throws failure
        justRun { f.statement.close() }

        // when
        val thrown = assertThrows(UncategorizedSQLException::class.java) {
            f.query.update(8, Changes("Beta", null, null))
        }

        // then
        assertSame(failure, thrown.sqlException)
        verify(exactly = 1) { f.statement.close() }
        assertEquals(1, f.tx.rollbacks)
    }

    @ParameterizedTest
    @MethodSource("roleChanges")
    fun `역할이 바뀔 때__수정하면__강등일 때만 같은 트랜잭션 안에서 모든 세션을 폐기한다`(
        current: Role,
        requested: Role,
        revokes: Boolean,
    ) {
        // given
        val f = Fixture()
        f.jdbc.role = current
        f.jdbc.care = if (current == Role.ROLE_SHELTER) "A" else null
        val revoked = ArrayList<Long>()
        val transaction = TransactionTemplate(f.tx)
        transaction.timeout = 15
        val query = JdbcAdminUserUpdate(
            f.jdbc,
            transaction,
            Supplier { MODIFIED },
            UserSessionRevoker { userId ->
                assertTrue(f.tx.active)
                assertEquals("update", f.jdbc.events.last())
                revoked.add(userId)
            },
        )

        every { f.connection.prepareStatement(any()) } returns f.statement
        justRun { f.statement.setString(any(), any()) }
        justRun { f.statement.setTimestamp(any(), any(), any()) }
        justRun { f.statement.setLong(any(), any()) }

        // when
        query.update(8, Changes(null, requested, null))

        // then
        assertEquals(if (revokes) listOf(8L) else listOf<Long>(), revoked)
        assertEquals(1, f.tx.commits)
    }

    @Test
    fun `강등의 세션 폐기가 실패할 때__수정하면__역할 변경도 롤백한다`() {
        // given
        val f = Fixture()
        f.jdbc.role = Role.ROLE_ADMIN
        val transaction = TransactionTemplate(f.tx)
        transaction.timeout = 15
        val failure = DataAccessResourceFailureException("synthetic revoke")
        val query = JdbcAdminUserUpdate(
            f.jdbc,
            transaction,
            Supplier { MODIFIED },
            UserSessionRevoker { userId -> throw failure },
        )

        every { f.connection.prepareStatement(any()) } returns f.statement
        justRun { f.statement.setString(any(), any()) }
        justRun { f.statement.setTimestamp(any(), any(), any()) }
        justRun { f.statement.setLong(any(), any()) }

        // when
        val thrown = assertThrows(RuntimeException::class.java) { query.update(8, Changes(null, Role.ROLE_USER, null)) }

        // then
        assertSame(failure, thrown)
        assertEquals(1, f.tx.rollbacks)
        assertEquals(0, f.tx.commits)
    }

    private class Fixture {
        val tx = RecordingTransactions()
        val connection: Connection = mockk<Connection>()
        val statement: PreparedStatement = mockk<PreparedStatement>()
        val jdbc = RecordingJdbc(tx, connection)
        var samples = 0
        val query: JdbcAdminUserUpdate

        init {
            val transaction = TransactionTemplate(tx)
            transaction.timeout = 15
            query = JdbcAdminUserUpdate(
                jdbc,
                transaction,
                Supplier {
                    assertTrue(tx.active)
                    samples++
                    MODIFIED
                },
            )
        }
    }

    private class RecordingJdbc(
        private val tx: RecordingTransactions,
        private val connection: Connection,
    ) : JdbcTemplate() {
        val events = ArrayList<String>()
        var present = true
        var duplicate = false
        var role = Role.ROLE_USER
        var care: String? = null
        var lockSql: String? = null
        var duplicateSql: String? = null
        var lockArguments: Array<out Any?>? = null
        var duplicateArguments: Array<out Any?>? = null
        var changed = 1
        var updateFailure: RuntimeException? = null

        override fun <T> query(sql: String, rse: ResultSetExtractor<T>, vararg args: Any?): T? {
            assertTrue(tx.active)
            events.add("lock")
            lockSql = normalized(sql)
            lockArguments = args
            try {
                return rse.extractData(row())
            } catch (e: SQLException) {
                throw AssertionError(e)
            }
        }

        @Suppress("UNCHECKED_CAST")
        override fun <T> queryForObject(sql: String, requiredType: Class<T>, vararg args: Any?): T? {
            assertTrue(tx.active)
            events.add("duplicate")
            duplicateSql = normalized(sql)
            duplicateArguments = args
            return java.lang.Boolean.valueOf(duplicate) as T
        }

        /** A tiny in-memory row: ResultSet has too many overloads to stub one by one with strict mocks. */
        private fun row(): ResultSet {
            val values = mapOf("nickname" to "Alpha", "role" to role.name, "care_reg_no" to care)
            return Proxy.newProxyInstance(
                ResultSet::class.java.classLoader,
                arrayOf(ResultSet::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "next" -> present
                    "getString" -> values.getValue(args[0] as String)
                    else -> throw UnsupportedOperationException(method.name)
                }
            } as ResultSet
        }

        override fun update(psc: PreparedStatementCreator): Int {
            assertTrue(tx.active)
            events.add("update")
            try {
                psc.createPreparedStatement(connection)
            } catch (e: SQLException) {
                throw UncategorizedSQLException("owned unit", null, e)
            }
            if (updateFailure != null) {
                throw updateFailure!!
            }
            return changed
        }
    }

    private class RecordingTransactions : PlatformTransactionManager {
        var active = false
        var starts = 0
        var commits = 0
        var rollbacks = 0
        var beginFailure: RuntimeException? = null
        var commitFailure: RuntimeException? = null

        override fun getTransaction(definition: TransactionDefinition?): TransactionStatus {
            assertFalse(active)
            assertFalse(definition!!.isReadOnly)
            assertEquals(15, definition.timeout)
            assertEquals(TransactionDefinition.ISOLATION_DEFAULT, definition.isolationLevel)
            starts++
            if (beginFailure != null) {
                throw beginFailure!!
            }
            active = true
            return SimpleTransactionStatus()
        }

        override fun commit(status: TransactionStatus) {
            active = false
            commits++
            if (commitFailure != null) {
                throw commitFailure!!
            }
        }

        override fun rollback(status: TransactionStatus) {
            active = false
            rollbacks++
        }
    }

    companion object {
        private val MODIFIED: LocalDateTime = LocalDateTime.of(2030, 2, 3, 4, 5, 6, 123456000)

        @JvmStatic
        fun approvalRequests(): Stream<Arguments> = Stream.of(
            Arguments.of(Role.ROLE_USER, null, Changes("Duplicate", Role.ROLE_SHELTER, null)),
            Arguments.of(Role.ROLE_SHELTER, "A", Changes("Duplicate", null, "B")),
            Arguments.of(Role.ROLE_SHELTER, null, Changes(null, null, "A")),
        )

        @JvmStatic
        fun noChanges(): Stream<Changes> = Stream.of(
            Changes(null, null, null),
            Changes("Alpha", Role.ROLE_USER, "A"),
            Changes(null, null, "  "),
            Changes("", null, null),
        )

        @JvmStatic
        fun validChanges(): Stream<Arguments> = Stream.of(
            Arguments.of(Role.ROLE_USER, null, Changes("123", null, null), "123", Role.ROLE_USER),
            Arguments.of(Role.ROLE_USER, null, Changes(null, Role.ROLE_ADMIN, null), "Alpha", Role.ROLE_ADMIN),
            Arguments.of(Role.ROLE_SHELTER, "A", Changes(null, Role.ROLE_USER, "A"), "Alpha", Role.ROLE_USER),
            Arguments.of(Role.ROLE_SHELTER, "A", Changes("Beta", Role.ROLE_SHELTER, "A"), "Beta", Role.ROLE_SHELTER),
        )

        @JvmStatic
        fun roleChanges(): Stream<Arguments> = Stream.of(
            Arguments.of(Role.ROLE_ADMIN, Role.ROLE_USER, true),
            Arguments.of(Role.ROLE_SHELTER, Role.ROLE_USER, true),
            Arguments.of(Role.ROLE_USER, Role.ROLE_ADMIN, false),
            Arguments.of(Role.ROLE_SHELTER, Role.ROLE_ADMIN, false),
        )
    }
}
