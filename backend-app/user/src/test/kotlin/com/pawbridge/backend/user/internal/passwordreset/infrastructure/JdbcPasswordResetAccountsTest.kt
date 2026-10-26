package com.pawbridge.backend.user.internal.passwordreset.infrastructure

import com.pawbridge.backend.user.internal.session.application.UserSessionRevoker
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
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
    private val jdbc = mockk<JdbcOperations>()
    private val accounts = JdbcPasswordResetAccounts(jdbc, Supplier { NOW })

    @Test
    fun `이메일이 있을 때__조회하면__대소문자 무시 로컬 쿼리가 id만 투영한다`() {
        // given
        every {
            jdbc.query(any<String>(), any<RowMapper<Long>>(), *anyVararg<Any>())
        } returnsMany listOf(mutableListOf(7L), mutableListOf(), mutableListOf(7L, 8L))

        // when
        val found = accounts.findLocalUserId("a@b.c")
        val missing = accounts.findLocalUserId("a@b.c")

        // then
        assertEquals(OptionalLong.of(7), found)
        assertEquals(OptionalLong.empty(), missing)
        assertThrows(IncorrectResultSizeDataAccessException::class.java) { accounts.findLocalUserId("a@b.c") }
        val sql = mutableListOf<String>()
        verify(exactly = 3) { jdbc.query(capture(sql), any<RowMapper<Long>>(), "a@b.c", "LOCAL") }
        assertEquals(
            "SELECT user_id FROM pawbridge_user.users WHERE lower(email) = lower(?) AND lower(provider) = lower(?)",
            sql.last().replace(Regex("\\s+"), " ").trim(),
        )
    }

    @Test
    fun `회원이 있을 때__갱신하면__비밀번호와 updated_at만 UTC 바인딩으로 쓴다`() {
        // given
        val connection = mockk<Connection>()
        val statement = mockk<PreparedStatement>()

        every { connection.prepareStatement(any<String>()) } returns statement
        justRun { statement.setString(any(), any()) }
        justRun { statement.setTimestamp(any(), any(), any<Calendar>()) }
        justRun { statement.setLong(any(), any()) }
        every { jdbc.update(any<PreparedStatementCreator>()) } answers {
            firstArg<PreparedStatementCreator>().createPreparedStatement(connection)
            1
        }

        // when
        accounts.updatePassword(7, "ENC")

        // then
        val sql = slot<String>()
        val calendar = slot<Calendar>()
        verify(exactly = 1) { connection.prepareStatement(capture(sql)) }
        assertEquals(
            "UPDATE pawbridge_user.users SET password = ?, updated_at = ? WHERE user_id = ?",
            sql.captured.replace(Regex("\\s+"), " ").trim(),
        )
        verify(exactly = 1) { statement.setString(1, "ENC") }
        verify(exactly = 1) { statement.setTimestamp(2, Timestamp.valueOf(NOW), capture(calendar)) }
        assertEquals(TimeZone.getTimeZone("UTC"), calendar.captured.timeZone)
        verify(exactly = 1) { statement.setLong(3, 7) }
    }

    @Test
    fun `바뀐 행이 없을 때__갱신하면__실패한다`() {
        // given
        every { jdbc.update(any<PreparedStatementCreator>()) } returns 0

        // when
        // then
        assertThrows(IllegalStateException::class.java) { accounts.updatePassword(7, "ENC") }
    }

    @Test
    fun `재설정에 성공했을 때__갱신하면__쓰기 뒤에 회원의 모든 세션을 폐기한다`() {
        // given
        val events = ArrayList<String>()
        every { jdbc.update(any<PreparedStatementCreator>()) } answers {
            events.add("password")
            1
        }
        val revoking = JdbcPasswordResetAccounts(jdbc, Supplier { NOW }, UserSessionRevoker { userId -> events.add("revoke-" + userId) })

        // when
        revoking.updatePassword(7, "ENC")

        // then
        assertEquals(listOf("password", "revoke-7"), events)
    }

    @Test
    fun `바뀐 행이 없을 때__재설정하면__세션을 폐기하지 않는다`() {
        // given
        every { jdbc.update(any<PreparedStatementCreator>()) } returns 0
        val revoked = ArrayList<Long>()
        val revoking = JdbcPasswordResetAccounts(jdbc, Supplier { NOW }, UserSessionRevoker { userId -> revoked.add(userId) })

        // when
        assertThrows(IllegalStateException::class.java) { revoking.updatePassword(7, "ENC") }

        // then
        assertEquals(listOf<Long>(), revoked)
    }

    companion object {
        private val NOW: LocalDateTime = LocalDateTime.of(2026, 1, 2, 3, 4, 5, 123456000)
    }
}
