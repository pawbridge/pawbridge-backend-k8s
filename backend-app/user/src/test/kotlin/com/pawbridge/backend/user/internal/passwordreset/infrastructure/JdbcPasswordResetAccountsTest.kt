package com.pawbridge.backend.user.internal.passwordreset.infrastructure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import com.pawbridge.backend.user.internal.session.application.UserSessionRevoker
import org.springframework.dao.IncorrectResultSizeDataAccessException
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.PreparedStatementCreator
import org.springframework.jdbc.core.RowMapper
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.time.LocalDateTime
import java.util.Calendar
import java.util.OptionalLong
import java.util.TimeZone
import java.util.function.Supplier

class JdbcPasswordResetAccountsTest {
    private val jdbc: JdbcOperations = mock(JdbcOperations::class.java)
    private val accounts = JdbcPasswordResetAccounts(jdbc, Supplier { NOW })

    @Test
    fun given__email__when__looking_up__then__case_insensitive_local_query_projects_only_the_id() {
        `when`(jdbc.query(any(String::class.java), any<RowMapper<Long>>(), eq("a@b.c"), eq("LOCAL")))
            .thenReturn(listOf(7L), listOf(), listOf(7L, 8L))

        assertEquals(OptionalLong.of(7), accounts.findLocalUserId("a@b.c"))
        assertEquals(OptionalLong.empty(), accounts.findLocalUserId("a@b.c"))
        assertThrows(IncorrectResultSizeDataAccessException::class.java) { accounts.findLocalUserId("a@b.c") }

        val sql = ArgumentCaptor.forClass(String::class.java)
        verify(jdbc, times(3)).query(sql.capture(), any<RowMapper<Long>>(), eq("a@b.c"), eq("LOCAL"))
        assertEquals(
            "SELECT user_id FROM pawbridge_user.users WHERE lower(email) = lower(?) AND lower(provider) = lower(?)",
            sql.value.replace(Regex("\\s+"), " ").trim(),
        )
    }

    @Test
    fun given__user__when__updating__then__only_password_and_updated_at_are_written_with_utc_binding() {
        val connection = mock(Connection::class.java)
        val statement = mock(PreparedStatement::class.java)
        val sql = ArgumentCaptor.forClass(String::class.java)
        `when`(connection.prepareStatement(sql.capture())).thenReturn(statement)
        doAnswer { invocation ->
            invocation.getArgument<PreparedStatementCreator>(0).createPreparedStatement(connection)
            1
        }.`when`(jdbc).update(any(PreparedStatementCreator::class.java))

        accounts.updatePassword(7, "ENC")

        assertEquals(
            "UPDATE pawbridge_user.users SET password = ?, updated_at = ? WHERE user_id = ?",
            sql.value.replace(Regex("\\s+"), " ").trim(),
        )
        verify(statement).setString(1, "ENC")
        val calendar = ArgumentCaptor.forClass(Calendar::class.java)
        verify(statement).setTimestamp(eq(2), eq(Timestamp.valueOf(NOW)), calendar.capture())
        assertEquals(TimeZone.getTimeZone("UTC"), calendar.value.timeZone)
        verify(statement).setLong(3, 7)
    }

    @Test
    fun given__no_row_changed__when__updating__then__fail() {
        `when`(jdbc.update(any(PreparedStatementCreator::class.java))).thenReturn(0)

        assertThrows(IllegalStateException::class.java) { accounts.updatePassword(7, "ENC") }
    }

    @Test
    fun given__a_successful_reset__when__updating__then__revoke_every_session_of_the_member_after_the_write() {
        val events = ArrayList<String>()
        `when`(jdbc.update(any(PreparedStatementCreator::class.java))).thenAnswer {
            events.add("password")
            1
        }
        val revoking = JdbcPasswordResetAccounts(jdbc, Supplier { NOW }, UserSessionRevoker { userId -> events.add("revoke-" + userId) })

        revoking.updatePassword(7, "ENC")

        assertEquals(listOf("password", "revoke-7"), events)
    }

    @Test
    fun given__no_row_changed__when__resetting__then__do_not_revoke_sessions() {
        `when`(jdbc.update(any(PreparedStatementCreator::class.java))).thenReturn(0)
        val revoked = ArrayList<Long>()
        val revoking = JdbcPasswordResetAccounts(jdbc, Supplier { NOW }, UserSessionRevoker { userId -> revoked.add(userId) })

        assertThrows(IllegalStateException::class.java) { revoking.updatePassword(7, "ENC") }

        assertEquals(listOf<Long>(), revoked)
    }

    companion object {
        private val NOW: LocalDateTime = LocalDateTime.of(2026, 1, 2, 3, 4, 5, 123456000)
    }
}
