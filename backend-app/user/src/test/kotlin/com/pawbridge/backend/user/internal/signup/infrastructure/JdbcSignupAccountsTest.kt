package com.pawbridge.backend.user.internal.signup.infrastructure

import com.pawbridge.backend.user.internal.signup.application.SignedUp
import com.pawbridge.backend.user.internal.signup.application.SignupRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.postgresql.util.PSQLException
import org.postgresql.util.ServerErrorMessage
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.PreparedStatementCreator
import org.springframework.jdbc.support.KeyHolder
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.time.LocalDateTime
import java.util.ArrayDeque
import java.util.Calendar
import java.util.TimeZone
import java.util.function.Supplier

class JdbcSignupAccountsTest {
    private val jdbc: JdbcOperations = mock(JdbcOperations::class.java)
    private val nicknames = ArrayDeque<String>(listOf("첫번째닉네임", "두번째닉네임"))
    private val accounts = JdbcSignupAccounts(jdbc, Supplier { nicknames.removeFirst() }, Supplier { NOW })

    @Test
    fun given__email__when__checking_duplicates__then__case_insensitive_local_provider_query() {
        `when`(jdbc.queryForObject(any(String::class.java), eq(Boolean::class.javaObjectType), eq("a@b.c"), eq("LOCAL")))
            .thenReturn(true, false, null)

        assertTrue(accounts.existsLocalEmail("a@b.c"))
        assertFalse(accounts.existsLocalEmail("a@b.c"))
        assertFalse(accounts.existsLocalEmail("a@b.c"))

        val sql = ArgumentCaptor.forClass(String::class.java)
        verify(jdbc, times(3)).queryForObject(sql.capture(), eq(Boolean::class.javaObjectType), eq("a@b.c"), eq("LOCAL"))
        assertEquals(
            "SELECT (count(*) > 0) FROM pawbridge_user.users WHERE lower(email) = lower(?) AND lower(provider) = lower(?)",
            sql.value.replace(Regex("\\s+"), " ").trim(),
        )
    }

    @Test
    fun given__new_account__when__creating__then__insert_binds_local_user_columns_with_utc_timestamps_and_returns_key() {
        val statement = capture(7L)

        val saved = accounts.createLocalUser("a@b.c", "Kim", "ENC", SignupRole.ROLE_USER, null)

        assertEquals(SignedUp(7, "a@b.c", "Kim", "첫번째닉네임"), saved)
        verify(statement).setString(1, "a@b.c")
        verify(statement).setString(2, "Kim")
        verify(statement).setString(3, "첫번째닉네임")
        verify(statement).setString(4, "ENC")
        verify(statement).setString(5, "ROLE_USER")
        verify(statement).setString(6, null)
        verify(statement).setString(7, "LOCAL")
        verify(statement).setString(8, null)
        val calendar = ArgumentCaptor.forClass(Calendar::class.java)
        verify(statement).setTimestamp(eq(9), eq(Timestamp.valueOf(NOW)), calendar.capture())
        verify(statement).setTimestamp(eq(10), eq(Timestamp.valueOf(NOW)), any(Calendar::class.java))
        assertEquals(TimeZone.getTimeZone("UTC"), calendar.value.timeZone)
    }

    @Test
    fun given__nickname_conflict__when__creating__then__regenerates_once_in_the_same_unit_of_work() {
        val attempts = IntArray(1)
        doAnswer { invocation ->
            if (attempts[0]++ == 0) {
                throw DataIntegrityViolationException("duplicate nickname")
            }
            invocation.getArgument<KeyHolder>(1).keyList.add(mutableMapOf<String, Any>("user_id" to 9L))
            1
        }.`when`(jdbc).update(any(PreparedStatementCreator::class.java), any(KeyHolder::class.java))

        val saved = accounts.createLocalUser("a@b.c", "Kim", "ENC", SignupRole.ROLE_USER, null)

        assertEquals(SignedUp(9, "a@b.c", "Kim", "두번째닉네임"), saved)
        assertEquals(2, attempts[0])
    }

    @Test
    fun given__second_conflict__when__creating__then__failure_propagates_after_one_retry() {
        doAnswer { throw DataIntegrityViolationException("duplicate") }
            .`when`(jdbc).update(any(PreparedStatementCreator::class.java), any(KeyHolder::class.java))

        assertThrows(DataIntegrityViolationException::class.java) {
            accounts.createLocalUser("a@b.c", "Kim", "ENC", SignupRole.ROLE_USER, null)
        }

        verify(jdbc, times(2)).update(any(PreparedStatementCreator::class.java), any(KeyHolder::class.java))
    }

    @Test
    fun given__rollback_charset_check_failure__when__creating__then__rethrown_without_retry() {
        val postgres = mock(PSQLException::class.java)
        val server = mock(ServerErrorMessage::class.java)
        `when`(postgres.sqlState).thenReturn("23514")
        `when`(postgres.serverErrorMessage).thenReturn(server)
        `when`(server.schema).thenReturn("pawbridge_user")
        `when`(server.constraint).thenReturn("ck_rollback_charset_users_name")
        val failure = DataIntegrityViolationException("check", postgres)
        doAnswer { throw failure }
            .`when`(jdbc).update(any(PreparedStatementCreator::class.java), any(KeyHolder::class.java))

        assertSame(
            failure,
            assertThrows(DataIntegrityViolationException::class.java) {
                accounts.createLocalUser("a@b.c", "Kim", "ENC", SignupRole.ROLE_USER, null)
            },
        )

        verify(jdbc, times(1)).update(any(PreparedStatementCreator::class.java), any(KeyHolder::class.java))
        assertTrue(RollbackCharsetViolation.matches(failure))
        assertFalse(RollbackCharsetViolation.matches(DataIntegrityViolationException("other")))
    }

    @Test
    fun given__no_generated_key__when__creating__then__fail_instead_of_returning_a_partial_result() {
        `when`(jdbc.update(any(PreparedStatementCreator::class.java), any(KeyHolder::class.java))).thenReturn(1)

        assertThrows(IllegalStateException::class.java) {
            accounts.createLocalUser("a@b.c", "Kim", "ENC", SignupRole.ROLE_USER, null)
        }
    }

    private fun capture(key: Long): PreparedStatement {
        val connection = mock(Connection::class.java)
        val statement = mock(PreparedStatement::class.java)
        `when`(connection.prepareStatement(any(String::class.java), any(Array<String>::class.java))).thenReturn(statement)
        doAnswer { invocation ->
            invocation.getArgument<PreparedStatementCreator>(0).createPreparedStatement(connection)
            invocation.getArgument<KeyHolder>(1).keyList.add(mutableMapOf<String, Any>("user_id" to key))
            1
        }.`when`(jdbc).update(any(PreparedStatementCreator::class.java), any(KeyHolder::class.java))
        return statement
    }

    companion object {
        private val NOW: LocalDateTime = LocalDateTime.of(2026, 1, 2, 3, 4, 5, 123456000)
    }
}
