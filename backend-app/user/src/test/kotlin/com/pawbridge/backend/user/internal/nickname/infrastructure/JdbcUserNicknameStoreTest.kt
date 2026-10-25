package com.pawbridge.backend.user.internal.nickname.infrastructure

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

class JdbcUserNicknameStoreTest {
    @Test
    fun `닉네임이 있는 회원과 없는 회원일 때__조회하면__두 컬럼 투영이 원문 닉네임과 부재를 보존한다`() {
        // given
        val jdbc = RecordingJdbc()
        val store = JdbcUserNicknameStore(jdbc)

        every { jdbc.rows.next() } returnsMany listOf(true, false)
        every { jdbc.rows.getString("nickname") } returns "MiXeD닉네임"

        // when
        val present = store.findNickname(52L)

        // then
        assertEquals(Optional.of("MiXeD닉네임"), present)
        assertEquals("SELECT user_id, nickname FROM pawbridge_user.users WHERE user_id = ?", normalized(jdbc.querySql!!))
        assertArrayEquals(arrayOf<Any?>(52L), jdbc.arguments)
        assertEquals(Optional.empty<String>(), store.findNickname(53L))
    }

    @Test
    fun `대소문자가 섞인 입력일 때__중복을 확인하면__자기 제외 없이 원래 문자열을 데이터베이스 lower 쿼리에 넘긴다`() {
        // given
        val jdbc = RecordingJdbc()
        val store = JdbcUserNicknameStore(jdbc)

        // when
        val exists = store.existsByNickname("MiXeD닉네임")

        // then
        assertFalse(exists)
        assertEquals(
            "SELECT (count(*) > 0) FROM pawbridge_user.users WHERE lower(nickname) = lower(?)",
            normalized(jdbc.querySql!!),
        )
        assertArrayEquals(arrayOf<Any?>("MiXeD닉네임"), jdbc.arguments)
    }

    @Test
    fun `사용 가능한 닉네임과 감사 시각일 때__갱신하면__닉네임과 updated_at만 UTC 캘린더로 바인딩한다`() {
        // given
        val jdbc = RecordingJdbc()
        val store = JdbcUserNicknameStore(jdbc, Supplier { UPDATED })

        every { jdbc.connection.prepareStatement(any<String>()) } answers {
            jdbc.updateSql = firstArg()
            jdbc.statement
        }
        justRun { jdbc.statement.setString(any(), any()) }
        justRun { jdbc.statement.setTimestamp(any(), any(), any<Calendar>()) }
        justRun { jdbc.statement.setLong(any(), any()) }

        // when
        store.updateNickname(52L, "새Nick9")

        // then
        val calendar = slot<Calendar>()
        assertEquals("UPDATE pawbridge_user.users SET nickname = ?, updated_at = ? WHERE user_id = ?", normalized(jdbc.updateSql!!))
        verify(exactly = 1) { jdbc.statement.setString(1, "새Nick9") }
        verify(exactly = 1) { jdbc.statement.setTimestamp(2, Timestamp.valueOf(UPDATED), capture(calendar)) }
        assertEquals("UTC", calendar.captured.timeZone.id)
        verify(exactly = 1) { jdbc.statement.setLong(3, 52L) }
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 2])
    fun `예상 밖의 갱신 행 수일 때__갱신하면__회원 없음이나 성공이 아닌 예기치 않은 실패로 처리한다`(count: Int) {
        // given
        val jdbc = RecordingJdbc()
        jdbc.changed = count
        val store = JdbcUserNicknameStore(jdbc, Supplier { UPDATED })

        every { jdbc.connection.prepareStatement(any<String>()) } answers {
            jdbc.updateSql = firstArg()
            jdbc.statement
        }
        justRun { jdbc.statement.setString(any(), any()) }
        justRun { jdbc.statement.setTimestamp(any(), any(), any<Calendar>()) }
        justRun { jdbc.statement.setLong(any(), any()) }

        // when
        // then
        assertThrows(IllegalStateException::class.java) { store.updateNickname(52L, "새Nick9") }
    }

    @Test
    fun `파라미터 바인딩이 실패했을 때__갱신하면__JDBC가 실패를 전파하기 전에 문장을 닫는다`() {
        // given
        val jdbc = RecordingJdbc()
        val failure = SQLException("synthetic private binding failure")
        val store = JdbcUserNicknameStore(jdbc, Supplier { UPDATED })

        every { jdbc.connection.prepareStatement(any<String>()) } answers {
            jdbc.updateSql = firstArg()
            jdbc.statement
        }
        justRun { jdbc.statement.setString(any(), any()) }
        every { jdbc.statement.setTimestamp(any(), any(), any<Calendar>()) } throws failure
        justRun { jdbc.statement.close() }

        // when
        val thrown = assertThrows(UncategorizedSQLException::class.java) { store.updateNickname(52L, "새Nick9") }

        // then
        assertSame(failure, thrown.sqlException)
        verify(exactly = 1) { jdbc.statement.close() }
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
                throw UncategorizedSQLException("nickname lookup", sql, failure)
            }
        }

        override fun <T> queryForObject(sql: String, requiredType: Class<T>, vararg args: Any?): T? {
            querySql = sql
            arguments = args
            return requiredType.cast(java.lang.Boolean.FALSE)
        }

        override fun update(psc: PreparedStatementCreator): Int {
            try {
                psc.createPreparedStatement(connection)
                return changed
            } catch (failure: SQLException) {
                throw UncategorizedSQLException("nickname update", updateSql, failure)
            }
        }
    }

    companion object {
        private val UPDATED: LocalDateTime = LocalDateTime.of(2026, 1, 2, 3, 4, 5, 123456000)
    }
}
