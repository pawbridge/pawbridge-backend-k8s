package com.pawbridge.backend.community.internal.comment.infrastructure

import com.pawbridge.backend.community.internal.comment.application.UnsupportedCommentCharactersException
import io.mockk.confirmVerified
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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.postgresql.util.PSQLException
import org.postgresql.util.ServerErrorMessage
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.PreparedStatementCreator
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionOperations
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Calendar
import java.util.stream.Stream

class JdbcCommentModificationTest {
    private val jdbc = mockk<JdbcOperations>()
    private val transactions = RecordingTransactions()
    private val connection = mockk<Connection>()
    private val statement = mockk<PreparedStatement>()
    private val clock = mockk<Clock>()
    private val writer = JdbcCommentWriter(jdbc, transactions, clock)
    private var updateCount = 1
    private var updateAttempts = 0

    @Test
    fun `내용이 바뀐 원문일 때__수정하면__읽기 없이 id만 조건으로 한 UPDATE 한 번에 내용과 감사 시각을 바인딩한다`() {
        // given
        val content = "  수정 원문\n　 "

        every { clock.zone } returns ZONE
        every { clock.instant() } answers {
            assertTrue(transactions.active)
            UPDATED
        }
        every { connection.prepareStatement(any<String>()) } answers {
            assertTrue(transactions.active)
            statement
        }
        every { jdbc.update(any<PreparedStatementCreator>()) } answers {
            updateAttempts++
            firstArg<PreparedStatementCreator>().createPreparedStatement(connection)
            updateCount
        }
        justRun { statement.setString(any(), any()) }
        justRun { statement.setTimestamp(any(), any<Timestamp>(), any<Calendar>()) }
        justRun { statement.setLong(any(), any()) }

        // when
        writer.update(13L, content)

        // then
        assertFalse(transactions.active)
        assertEquals(1, updateAttempts)
        val sql = slot<String>()
        verify(exactly = 1) { connection.prepareStatement(capture(sql)) }
        assertEquals(
            "UPDATE pawbridge_community.comments SET content = ?, updated_at = ? WHERE comment_id = ?",
            sql.captured.replace(Regex("\\s+"), " ").trim(),
        )
        verify(exactly = 1) { statement.setString(1, content) }
        val timestamp = slot<Timestamp>()
        val calendar = slot<Calendar>()
        verify(exactly = 1) { statement.setTimestamp(2, capture(timestamp), capture(calendar)) }
        assertEquals(Timestamp.valueOf(LocalDateTime.ofInstant(UPDATED, ZONE)), timestamp.captured)
        assertEquals(123456789, timestamp.captured.nanos)
        assertEquals("UTC", calendar.captured.timeZone.id)
        verify(exactly = 1) { statement.setLong(3, 13L) }
        verify(exactly = 1) { clock.instant() }
        assertEquals(1, transactions.executions)
        verify(exactly = 1) { jdbc.update(any<PreparedStatementCreator>()) }
        confirmVerified(jdbc)
    }

    @Test
    fun `스냅샷 이후 행이 사라졌을 때__수정하면__영향 행 0건을 재시도 없이 예상 밖 실패로 처리한다`() {
        // given
        updateCount = 0

        every { clock.zone } returns ZONE
        every { clock.instant() } returns UPDATED
        every { connection.prepareStatement(any<String>()) } returns statement
        every { jdbc.update(any<PreparedStatementCreator>()) } answers {
            updateAttempts++
            firstArg<PreparedStatementCreator>().createPreparedStatement(connection)
            updateCount
        }
        justRun { statement.setString(any(), any()) }
        justRun { statement.setTimestamp(any(), any<Timestamp>(), any<Calendar>()) }
        justRun { statement.setLong(any(), any()) }

        // when
        assertThrows(IllegalStateException::class.java) { writer.update(13L, "댓글") }

        // then
        assertFalse(transactions.active)
        assertEquals(1, updateAttempts)
        assertEquals(1, transactions.executions)
        verify(exactly = 1) { jdbc.update(any<PreparedStatementCreator>()) }
        confirmVerified(jdbc)
    }

    @Test
    fun `UPDATE SQL이 실패할 때__수정하면__재시도 없이 트랜잭션을 벗어나 원래 예외를 전파한다`() {
        // given
        val failure = DataAccessResourceFailureException("synthetic unavailable")

        every { clock.zone } returns ZONE
        every { clock.instant() } returns UPDATED
        every { jdbc.update(any<PreparedStatementCreator>()) } throws failure

        // when
        val thrown = assertThrows(DataAccessResourceFailureException::class.java) { writer.update(13L, "댓글") }

        // then
        assertSame(failure, thrown)
        assertFalse(transactions.active)
        assertEquals(1, transactions.executions)
        verify(exactly = 1) { jdbc.update(any<PreparedStatementCreator>()) }
        confirmVerified(jdbc)
    }

    @ParameterizedTest
    @MethodSource("commitFailures")
    fun `UPDATE 뒤 커밋이 실패할 때__수정하면__이름 붙은 커뮤니티 문자셋 거부만 사용자 오류로 바꾼다`(
        commitFailure: TransactionSystemException,
        translated: Boolean,
    ) {
        // given
        transactions.failureAfterCallback = commitFailure

        every { clock.zone } returns ZONE
        every { clock.instant() } returns UPDATED
        every { connection.prepareStatement(any<String>()) } returns statement
        every { jdbc.update(any<PreparedStatementCreator>()) } answers {
            updateAttempts++
            firstArg<PreparedStatementCreator>().createPreparedStatement(connection)
            updateCount
        }
        justRun { statement.setString(any(), any()) }
        justRun { statement.setTimestamp(any(), any<Timestamp>(), any<Calendar>()) }
        justRun { statement.setLong(any(), any()) }

        // when
        val thrown = assertThrows(RuntimeException::class.java) { writer.update(13L, "댓글") }

        // then
        if (translated) {
            assertTrue(thrown is UnsupportedCommentCharactersException)
            assertSame(commitFailure, thrown.cause)
            assertEquals("일부 이모지 등 지원하지 않는 문자를 제외해 주세요.", thrown.message)
        } else {
            assertSame(commitFailure, thrown)
        }
        assertFalse(transactions.active)
        assertEquals(1, updateAttempts)
        assertEquals(1, transactions.executions)
        verify(exactly = 1) { jdbc.update(any<PreparedStatementCreator>()) }
        confirmVerified(jdbc)
    }

    @Test
    fun `타임스탬프 바인딩과 닫기가 모두 실패할 때__UPDATE를 준비하면__문장을 닫고 원래 예외를 유지한다`() {
        // given
        val failure = IllegalStateException("synthetic binding failure")
        val closing = SQLException("synthetic close failure")

        every { clock.zone } returns ZONE
        every { clock.instant() } returns UPDATED
        every { connection.prepareStatement(any<String>()) } returns statement
        every { jdbc.update(any<PreparedStatementCreator>()) } answers {
            updateAttempts++
            firstArg<PreparedStatementCreator>().createPreparedStatement(connection)
            updateCount
        }
        justRun { statement.setString(any(), any()) }
        every { statement.setTimestamp(2, any<Timestamp>(), any<Calendar>()) } throws failure
        every { statement.close() } throws closing

        // when
        val thrown = assertThrows(IllegalStateException::class.java) { writer.update(13L, "댓글") }

        // then
        assertSame(failure, thrown)
        assertEquals(1, updateAttempts)
        assertFalse(transactions.active)
        assertEquals(1, failure.suppressed.size)
        assertSame(closing, failure.suppressed[0])
        verify(exactly = 1) { statement.close() }
    }

    /** An in-memory TransactionOperations that records running state and calls, and can fail after the callback. */
    private class RecordingTransactions : TransactionOperations {
        var active = false
        var executions = 0
        var failureAfterCallback: RuntimeException? = null

        override fun <T> execute(action: TransactionCallback<T>): T? {
            executions++
            active = true
            try {
                val result = action.doInTransaction(SimpleTransactionStatus())
                val failure = failureAfterCallback
                if (failure != null) {
                    throw failure
                }
                return result
            } finally {
                active = false
            }
        }
    }

    companion object {
        private val ZONE = ZoneId.of("Asia/Seoul")
        private val UPDATED = Instant.parse("2026-10-19T03:30:10.123456789Z")

        @JvmStatic
        fun commitFailures(): Stream<Arguments> =
            Stream.of(
                Arguments.of(TransactionSystemException("commit", constraint("ck_rollback_charset_comments")), true),
                Arguments.of(TransactionSystemException("commit", constraint("business_constraint")), false),
                Arguments.of(TransactionSystemException("commit outcome unknown"), false),
            )

        private fun constraint(name: String): PSQLException =
            PSQLException(
                ServerErrorMessage(
                    "SERROR\u0000C23514\u0000Msynthetic\u0000spawbridge_community\u0000n" + name + "\u0000",
                ),
            )
    }
}
