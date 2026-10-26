package com.pawbridge.backend.user.internal.signup.infrastructure

import com.pawbridge.backend.user.internal.signup.application.SignedUp
import com.pawbridge.backend.user.internal.signup.application.SignupRole
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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
    private val jdbc: JdbcOperations = mockk<JdbcOperations>()
    private val nicknames = ArrayDeque<String>(listOf("첫번째닉네임", "두번째닉네임"))
    private val accounts = JdbcSignupAccounts(jdbc, Supplier { nicknames.removeFirst() }, Supplier { NOW })

    @Test
    fun `이메일이 주어졌을 때__중복 여부를 확인하면__대소문자 구분 없이 LOCAL 제공자만 조회한다`() {
        // given
        every {
            jdbc.queryForObject(any<String>(), Boolean::class.javaObjectType, any<Any>(), any<Any>())
        } returnsMany listOf(true, false, null)

        // when
        val first = accounts.existsLocalEmail("a@b.c")
        val second = accounts.existsLocalEmail("a@b.c")
        val third = accounts.existsLocalEmail("a@b.c")

        // then
        assertTrue(first)
        assertFalse(second)
        assertFalse(third)
        val sql = mutableListOf<String>()
        verify(exactly = 3) { jdbc.queryForObject(capture(sql), Boolean::class.javaObjectType, "a@b.c", "LOCAL") }
        assertEquals(3, sql.size)
        for (statement in sql) {
            assertEquals(
                "SELECT (count(*) > 0) FROM pawbridge_user.users WHERE lower(email) = lower(?) AND lower(provider) = lower(?)",
                statement.replace(Regex("\\s+"), " ").trim(),
            )
        }
    }

    @Test
    fun `새 계정일 때__만들면__삽입 쿼리가 LOCAL 사용자 컬럼과 UTC 타임스탬프를 바인딩하고 키를 돌려준다`() {
        // given
        val connection = mockk<Connection>()
        val statement = mockk<PreparedStatement>()

        every { connection.prepareStatement(any<String>(), any<Array<String>>()) } returns statement
        justRun { statement.setString(any(), any()) }
        justRun { statement.setTimestamp(any(), any(), any()) }
        every { jdbc.update(any<PreparedStatementCreator>(), any<KeyHolder>()) } answers {
            firstArg<PreparedStatementCreator>().createPreparedStatement(connection)
            secondArg<KeyHolder>().keyList.add(mutableMapOf<String, Any>("user_id" to 7L))
            1
        }

        // when
        val saved = accounts.createLocalUser("a@b.c", "Kim", "ENC", SignupRole.ROLE_USER, null)

        // then
        assertEquals(SignedUp(7, "a@b.c", "Kim", "첫번째닉네임"), saved)
        val calendar = slot<Calendar>()
        verify(exactly = 1) { statement.setString(1, "a@b.c") }
        verify(exactly = 1) { statement.setString(2, "Kim") }
        verify(exactly = 1) { statement.setString(3, "첫번째닉네임") }
        verify(exactly = 1) { statement.setString(4, "ENC") }
        verify(exactly = 1) { statement.setString(5, "ROLE_USER") }
        verify(exactly = 1) { statement.setString(6, null) }
        verify(exactly = 1) { statement.setString(7, "LOCAL") }
        verify(exactly = 1) { statement.setString(8, null) }
        verify(exactly = 1) { statement.setTimestamp(9, Timestamp.valueOf(NOW), capture(calendar)) }
        verify(exactly = 1) { statement.setTimestamp(10, Timestamp.valueOf(NOW), any()) }
        assertEquals(TimeZone.getTimeZone("UTC"), calendar.captured.timeZone)
    }

    @Test
    fun `닉네임이 충돌할 때__만들면__같은 작업 단위에서 닉네임을 한 번 다시 만든다`() {
        // given
        var attempts = 0

        every { jdbc.update(any<PreparedStatementCreator>(), any<KeyHolder>()) } answers {
            if (attempts++ == 0) {
                throw DataIntegrityViolationException("duplicate nickname")
            }
            secondArg<KeyHolder>().keyList.add(mutableMapOf<String, Any>("user_id" to 9L))
            1
        }

        // when
        val saved = accounts.createLocalUser("a@b.c", "Kim", "ENC", SignupRole.ROLE_USER, null)

        // then
        assertEquals(SignedUp(9, "a@b.c", "Kim", "두번째닉네임"), saved)
        assertEquals(2, attempts)
    }

    @Test
    fun `두 번째에도 충돌할 때__만들면__한 번 재시도한 뒤 예외를 전파한다`() {
        // given
        every { jdbc.update(any<PreparedStatementCreator>(), any<KeyHolder>()) } throws
            DataIntegrityViolationException("duplicate")

        // when
        assertThrows(DataIntegrityViolationException::class.java) {
            accounts.createLocalUser("a@b.c", "Kim", "ENC", SignupRole.ROLE_USER, null)
        }

        // then
        verify(exactly = 2) { jdbc.update(any<PreparedStatementCreator>(), any<KeyHolder>()) }
    }

    @Test
    fun `롤백 문자셋 체크 제약이 실패할 때__만들면__재시도 없이 그대로 다시 던진다`() {
        // given
        val postgres = mockk<PSQLException>()
        val server = mockk<ServerErrorMessage>()
        val failure = DataIntegrityViolationException("check", postgres)

        every { postgres.sqlState } returns "23514"
        every { postgres.serverErrorMessage } returns server
        every { postgres.cause } returns null
        every { postgres.nextException } returns null
        every { server.schema } returns "pawbridge_user"
        every { server.constraint } returns "ck_rollback_charset_users_name"
        every { jdbc.update(any<PreparedStatementCreator>(), any<KeyHolder>()) } throws failure

        // when
        val thrown = assertThrows(DataIntegrityViolationException::class.java) {
            accounts.createLocalUser("a@b.c", "Kim", "ENC", SignupRole.ROLE_USER, null)
        }

        // then
        assertSame(failure, thrown)
        verify(exactly = 1) { jdbc.update(any<PreparedStatementCreator>(), any<KeyHolder>()) }
        assertTrue(RollbackCharsetViolation.matches(failure))
        assertFalse(RollbackCharsetViolation.matches(DataIntegrityViolationException("other")))
    }

    @Test
    fun `생성된 키가 없을 때__만들면__일부만 채운 결과를 돌려주지 않고 실패한다`() {
        // given
        every { jdbc.update(any<PreparedStatementCreator>(), any<KeyHolder>()) } returns 1

        // when
        assertThrows(IllegalStateException::class.java) {
            accounts.createLocalUser("a@b.c", "Kim", "ENC", SignupRole.ROLE_USER, null)
        }
    }

    companion object {
        private val NOW: LocalDateTime = LocalDateTime.of(2026, 1, 2, 3, 4, 5, 123456000)
    }
}
