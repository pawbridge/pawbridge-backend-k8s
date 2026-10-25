package com.pawbridge.backend.user.internal.nickname.infrastructure

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
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
    fun given__present_then_missing_user__when__looking_up__then__two_column_projection_preserves_raw_nickname_and_absence() {
        val jdbc = RecordingJdbc()
        `when`(jdbc.rows.next()).thenReturn(true, false)
        `when`(jdbc.rows.getString("nickname")).thenReturn("MiXeD닉네임")
        val store = JdbcUserNicknameStore(jdbc)

        assertEquals(Optional.of("MiXeD닉네임"), store.findNickname(52L))

        assertEquals("SELECT user_id, nickname FROM pawbridge_user.users WHERE user_id = ?", normalized(jdbc.querySql!!))
        assertArrayEquals(arrayOf<Any?>(52L), jdbc.arguments)
        assertEquals(Optional.empty<String>(), store.findNickname(53L))
    }

    @Test
    fun given__mixed_case_input__when__checking_duplicate__then__database_lower_query_receives_original_string_without_self_exclusion() {
        val jdbc = RecordingJdbc()
        val store = JdbcUserNicknameStore(jdbc)

        assertFalse(store.existsByNickname("MiXeD닉네임"))

        assertEquals(
            "SELECT (count(*) > 0) FROM pawbridge_user.users WHERE lower(nickname) = lower(?)",
            normalized(jdbc.querySql!!),
        )
        assertArrayEquals(arrayOf<Any?>("MiXeD닉네임"), jdbc.arguments)
    }

    @Test
    fun given__available_nickname_and_auditing_time__when__updating__then__only_nickname_and_updated_at_are_bound_with_utc_calendar() {
        val jdbc = RecordingJdbc()
        val store = JdbcUserNicknameStore(jdbc, Supplier { UPDATED })

        store.updateNickname(52L, "새Nick9")

        assertEquals("UPDATE pawbridge_user.users SET nickname = ?, updated_at = ? WHERE user_id = ?", normalized(jdbc.updateSql!!))
        verify(jdbc.statement).setString(1, "새Nick9")
        val calendar = ArgumentCaptor.forClass(Calendar::class.java)
        verify(jdbc.statement).setTimestamp(eq(2), eq(Timestamp.valueOf(UPDATED)), calendar.capture())
        assertEquals("UTC", calendar.value.timeZone.id)
        verify(jdbc.statement).setLong(3, 52L)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 2])
    fun given__unexpected_update_count__when__updating__then__unexpected_failure_is_not_missing_user_or_success(count: Int) {
        val jdbc = RecordingJdbc()
        jdbc.changed = count
        val store = JdbcUserNicknameStore(jdbc, Supplier { UPDATED })

        assertThrows(IllegalStateException::class.java) { store.updateNickname(52L, "새Nick9") }
    }

    @Test
    fun given__parameter_binding_failure__when__updating__then__statement_is_closed_before_jdbc_propagates_failure() {
        val jdbc = RecordingJdbc()
        val failure = SQLException("synthetic private binding failure")
        doThrow(failure).`when`(jdbc.statement).setTimestamp(eq(2), any(Timestamp::class.java), any(Calendar::class.java))
        val store = JdbcUserNicknameStore(jdbc, Supplier { UPDATED })

        val thrown = assertThrows(UncategorizedSQLException::class.java) { store.updateNickname(52L, "새Nick9") }

        assertSame(failure, thrown.sqlException)
        verify(jdbc.statement).close()
    }

    private fun normalized(sql: String): String {
        return sql.replace(Regex("\\s+"), " ").trim()
    }

    private class RecordingJdbc : JdbcTemplate() {
        val rows: ResultSet = mock(ResultSet::class.java)
        val connection: Connection = mock(Connection::class.java)
        val statement: PreparedStatement = mock(PreparedStatement::class.java)
        var querySql: String? = null
        var updateSql: String? = null
        var arguments: Array<out Any?>? = null
        var changed = 1

        init {
            `when`(connection.prepareStatement(anyString())).thenAnswer { invocation ->
                updateSql = invocation.getArgument(0, String::class.java)
                statement
            }
        }

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
