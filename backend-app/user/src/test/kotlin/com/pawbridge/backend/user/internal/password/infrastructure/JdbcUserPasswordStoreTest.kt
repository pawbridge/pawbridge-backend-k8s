package com.pawbridge.backend.user.internal.password.infrastructure

import com.pawbridge.backend.user.internal.password.application.StoredCredentials
import com.pawbridge.backend.user.internal.session.application.UserSessionRevoker
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.jdbc.UncategorizedSQLException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementCreator
import org.springframework.jdbc.core.ResultSetExtractor
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.LocalDateTime
import java.util.Calendar
import java.util.Optional
import java.util.function.Supplier

class JdbcUserPasswordStoreTest {
    @Test
    fun `회원이 있을 때와 없을 때__조회하면__다른 컬럼 없이 제공자와 원본 해시만 투영한다`() {
        // given
        val jdbc = RecordingJdbc()
        val store = JdbcUserPasswordStore(jdbc)

        every { jdbc.rows.next() } returnsMany listOf(true, false)
        every { jdbc.rows.getString("provider") } returns "LOCAL"
        every { jdbc.rows.getString("password") } returns "\$2a\$10\$synthetic"

        // when
        val present = store.findCredentials(52L)

        // then
        assertEquals(Optional.of(StoredCredentials("LOCAL", "\$2a\$10\$synthetic")), present)

        assertEquals("SELECT user_id, provider, password FROM pawbridge_user.users WHERE user_id = ?", normalized(jdbc.querySql!!))
        assertArrayEquals(arrayOf<Any?>(52L), jdbc.arguments)
        assertEquals(Optional.empty<StoredCredentials>(), store.findCredentials(53L))
    }

    @Test
    fun `비밀번호 없는 소셜 회원일 때__조회하면__널 해시를 보존한다`() {
        // given
        val jdbc = RecordingJdbc()
        val store = JdbcUserPasswordStore(jdbc)

        every { jdbc.rows.next() } returns true
        every { jdbc.rows.getString("provider") } returns "GOOGLE"
        every { jdbc.rows.getString("password") } returns null

        // when
        val result = store.findCredentials(52L)

        // then
        assertEquals(Optional.of(StoredCredentials("GOOGLE", null)), result)
    }

    @Test
    fun `인코딩된 비밀번호와 감사 시각일 때__갱신하면__비밀번호와 updated_at만 UTC 캘린더로 바인딩한다`() {
        // given
        val jdbc = RecordingJdbc()
        val store = JdbcUserPasswordStore(jdbc, Supplier { UPDATED })

        every { jdbc.connection.prepareStatement(any<String>()) } answers {
            jdbc.updateSql = firstArg()
            jdbc.statement
        }
        justRun { jdbc.statement.setString(any(), any()) }
        justRun { jdbc.statement.setTimestamp(any(), any(), any<Calendar>()) }
        justRun { jdbc.statement.setLong(any(), any()) }

        // when
        store.updatePassword(52L, "\$2a\$10\$synthetic.new")

        // then
        val calendar = slot<Calendar>()
        assertEquals("UPDATE pawbridge_user.users SET password = ?, updated_at = ? WHERE user_id = ?", normalized(jdbc.updateSql!!))
        verify(exactly = 1) { jdbc.statement.setString(1, "\$2a\$10\$synthetic.new") }
        verify(exactly = 1) { jdbc.statement.setTimestamp(2, Timestamp.valueOf(UPDATED), capture(calendar)) }
        assertEquals("UTC", calendar.captured.timeZone.id)
        verify(exactly = 1) { jdbc.statement.setLong(3, 52L) }
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 2])
    fun `예상 밖의 갱신 행 수일 때__갱신하면__성공이 아닌 예기치 않은 실패로 처리한다`(count: Int) {
        // given
        val jdbc = RecordingJdbc()
        jdbc.changed = count
        val store = JdbcUserPasswordStore(jdbc, Supplier { UPDATED })

        every { jdbc.connection.prepareStatement(any<String>()) } answers {
            jdbc.updateSql = firstArg()
            jdbc.statement
        }
        justRun { jdbc.statement.setString(any(), any()) }
        justRun { jdbc.statement.setTimestamp(any(), any(), any<Calendar>()) }
        justRun { jdbc.statement.setLong(any(), any()) }

        // when
        // then
        assertThrows(IllegalStateException::class.java) { store.updatePassword(52L, "\$2a\$10\$synthetic.new") }
    }

    @Test
    fun `파라미터 바인딩이 실패했을 때__갱신하면__JDBC가 실패를 전파하기 전에 문장을 닫는다`() {
        // given
        val jdbc = RecordingJdbc()
        val failure = SQLException("synthetic private binding failure")
        val store = JdbcUserPasswordStore(jdbc, Supplier { UPDATED })

        every { jdbc.connection.prepareStatement(any<String>()) } answers {
            jdbc.updateSql = firstArg()
            jdbc.statement
        }
        justRun { jdbc.statement.setString(any(), any()) }
        every { jdbc.statement.setTimestamp(any(), any(), any<Calendar>()) } throws failure
        justRun { jdbc.statement.close() }

        // when
        val thrown = assertThrows(UncategorizedSQLException::class.java) {
            store.updatePassword(52L, "\$2a\$10\$synthetic.new")
        }

        // then
        assertSame(failure, thrown.sqlException)
        verify(exactly = 1) { jdbc.statement.close() }
    }

    @Test
    fun `비밀번호 쓰기에 성공했을 때__갱신하면__그 뒤에 회원의 모든 세션을 폐기한다`() {
        // given
        val jdbc = RecordingJdbc()
        val revoked = ArrayList<Long>()
        val store = JdbcUserPasswordStore(
            jdbc,
            Supplier { UPDATED },
            UserSessionRevoker { userId ->
                assertEquals(
                    "UPDATE pawbridge_user.users SET password = ?, updated_at = ? WHERE user_id = ?",
                    normalized(jdbc.updateSql!!),
                )
                revoked.add(userId)
            },
        )

        every { jdbc.connection.prepareStatement(any<String>()) } answers {
            jdbc.updateSql = firstArg()
            jdbc.statement
        }
        justRun { jdbc.statement.setString(any(), any()) }
        justRun { jdbc.statement.setTimestamp(any(), any(), any<Calendar>()) }
        justRun { jdbc.statement.setLong(any(), any()) }

        // when
        store.updatePassword(52L, "\$2a\$10\$synthetic.new")

        // then
        assertEquals(listOf(52L), revoked)
    }

    @Test
    fun `예상 밖의 갱신 행 수일 때__갱신하면__세션을 폐기하지 않는다`() {
        // given
        val jdbc = RecordingJdbc()
        jdbc.changed = 0
        val revoked = ArrayList<Long>()
        val store = JdbcUserPasswordStore(jdbc, Supplier { UPDATED }, UserSessionRevoker { userId -> revoked.add(userId) })

        every { jdbc.connection.prepareStatement(any<String>()) } answers {
            jdbc.updateSql = firstArg()
            jdbc.statement
        }
        justRun { jdbc.statement.setString(any(), any()) }
        justRun { jdbc.statement.setTimestamp(any(), any(), any<Calendar>()) }
        justRun { jdbc.statement.setLong(any(), any()) }

        // when
        assertThrows(IllegalStateException::class.java) { store.updatePassword(52L, "\$2a\$10\$synthetic.new") }

        // then
        assertEquals(listOf<Long>(), revoked)
    }

    private fun normalized(sql: String): String {
        return sql.replace(Regex("\\s+"), " ").trim()
    }

    private class RecordingJdbc : JdbcTemplate() {
        val rows = mockk<ResultSet>()
        val connection = mockk<Connection>()
        val statement = mockk<PreparedStatement>()
        var querySql: String? = null
        var updateSql: String? = null
        var arguments: Array<out Any?>? = null
        var changed = 1

        override fun <T> query(sql: String, rse: ResultSetExtractor<T>, vararg args: Any?): T? {
            querySql = sql
            arguments = args
            try {
                return rse.extractData(rows)
            } catch (failure: SQLException) {
                throw UncategorizedSQLException("password lookup", sql, failure)
            }
        }

        override fun update(psc: PreparedStatementCreator): Int {
            try {
                psc.createPreparedStatement(connection)
                return changed
            } catch (failure: SQLException) {
                throw UncategorizedSQLException("password update", updateSql, failure)
            }
        }
    }

    companion object {
        private val UPDATED: LocalDateTime = LocalDateTime.of(2026, 1, 2, 3, 4, 5, 123456000)
    }
}
