package com.pawbridge.backend.user.internal.password.infrastructure

import com.pawbridge.backend.user.internal.password.application.StoredCredentials
import com.pawbridge.backend.user.internal.session.application.UserSessionRevoker
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
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

class JdbcUserPasswordStoreTest {
    @Test
    fun given__present_then_missing_user__when__looking_up__then__provider_and_raw_hash_are_projected_without_other_columns() {
        val jdbc = RecordingJdbc()
        `when`(jdbc.rows.next()).thenReturn(true, false)
        `when`(jdbc.rows.getString("provider")).thenReturn("LOCAL")
        `when`(jdbc.rows.getString("password")).thenReturn("\$2a\$10\$synthetic")
        val store = JdbcUserPasswordStore(jdbc)

        assertEquals(Optional.of(StoredCredentials("LOCAL", "\$2a\$10\$synthetic")), store.findCredentials(52L))

        assertEquals("SELECT user_id, provider, password FROM pawbridge_user.users WHERE user_id = ?", normalized(jdbc.querySql!!))
        assertArrayEquals(arrayOf<Any?>(52L), jdbc.arguments)
        assertEquals(Optional.empty<StoredCredentials>(), store.findCredentials(53L))
    }

    @Test
    fun given__social_member_without_password__when__looking_up__then__null_hash_is_preserved() {
        val jdbc = RecordingJdbc()
        `when`(jdbc.rows.next()).thenReturn(true)
        `when`(jdbc.rows.getString("provider")).thenReturn("GOOGLE")
        val store = JdbcUserPasswordStore(jdbc)

        assertEquals(Optional.of(StoredCredentials("GOOGLE", null)), store.findCredentials(52L))
    }

    @Test
    fun given__encoded_password_and_auditing_time__when__updating__then__only_password_and_updated_at_are_bound_with_utc_calendar() {
        val jdbc = RecordingJdbc()
        val store = JdbcUserPasswordStore(jdbc, Supplier { UPDATED })

        store.updatePassword(52L, "\$2a\$10\$synthetic.new")

        assertEquals("UPDATE pawbridge_user.users SET password = ?, updated_at = ? WHERE user_id = ?", normalized(jdbc.updateSql!!))
        verify(jdbc.statement).setString(1, "\$2a\$10\$synthetic.new")
        val calendar = ArgumentCaptor.forClass(Calendar::class.java)
        verify(jdbc.statement).setTimestamp(eq(2), eq(Timestamp.valueOf(UPDATED)), calendar.capture())
        assertEquals("UTC", calendar.value.timeZone.id)
        verify(jdbc.statement).setLong(3, 52L)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 2])
    fun given__unexpected_update_count__when__updating__then__unexpected_failure_is_not_success(count: Int) {
        val jdbc = RecordingJdbc()
        jdbc.changed = count
        val store = JdbcUserPasswordStore(jdbc, Supplier { UPDATED })

        assertThrows(IllegalStateException::class.java) { store.updatePassword(52L, "\$2a\$10\$synthetic.new") }
    }

    @Test
    fun given__parameter_binding_failure__when__updating__then__statement_is_closed_before_jdbc_propagates_failure() {
        val jdbc = RecordingJdbc()
        val failure = SQLException("synthetic private binding failure")
        doThrow(failure).`when`(jdbc.statement).setTimestamp(eq(2), any(Timestamp::class.java), any(Calendar::class.java))
        val store = JdbcUserPasswordStore(jdbc, Supplier { UPDATED })

        val thrown = assertThrows(UncategorizedSQLException::class.java) {
            store.updatePassword(52L, "\$2a\$10\$synthetic.new")
        }

        assertSame(failure, thrown.sqlException)
        verify(jdbc.statement).close()
    }

    @Test
    fun given__a_successful_password_write__when__updating__then__revoke_every_session_of_the_member_afterwards() {
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

        store.updatePassword(52L, "\$2a\$10\$synthetic.new")

        assertEquals(listOf(52L), revoked)
    }

    @Test
    fun given__an_unexpected_update_count__when__updating__then__do_not_revoke_sessions() {
        val jdbc = RecordingJdbc()
        jdbc.changed = 0
        val revoked = ArrayList<Long>()
        val store = JdbcUserPasswordStore(jdbc, Supplier { UPDATED }, UserSessionRevoker { userId -> revoked.add(userId) })

        assertThrows(IllegalStateException::class.java) { store.updatePassword(52L, "\$2a\$10\$synthetic.new") }

        assertEquals(listOf<Long>(), revoked)
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
