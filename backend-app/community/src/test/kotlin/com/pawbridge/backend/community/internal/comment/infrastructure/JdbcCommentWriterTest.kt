package com.pawbridge.backend.community.internal.comment.infrastructure

import com.pawbridge.backend.community.internal.comment.application.CommentSnapshot
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
import org.postgresql.util.PSQLException
import org.postgresql.util.ServerErrorMessage
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.PreparedStatementCreator
import org.springframework.jdbc.core.ResultSetExtractor
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionOperations
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Calendar

class JdbcCommentWriterTest {
    private val jdbc = mockk<JdbcOperations>()
    private val transactions = RecordingTransactions()
    private val connection = mockk<Connection>()
    private val statement = mockk<PreparedStatement>()
    private val result = mockk<ResultSet>()
    private val clock = Clock.fixed(Instant.parse("2026-10-19T03:30:10.123456789Z"), ZoneId.of("Asia/Seoul"))
    private val writer = JdbcCommentWriter(jdbc, transactions, clock)
    private var insertAttempts = 0

    @Test
    fun `댓글 내용과 시계가 주어졌을 때__작성하면__INSERT 한 번으로 식별자를 받아 전체 정밀도의 스냅샷을 돌려준다`() {
        // given
        val content = "  댓글 원문\n "
        val now = LocalDateTime.now(clock)

        every { jdbc.query(any<PreparedStatementCreator>(), any<ResultSetExtractor<Long>>()) } answers {
            assertTrue(transactions.active)
            insertAttempts++
            firstArg<PreparedStatementCreator>().createPreparedStatement(connection)
            secondArg<ResultSetExtractor<Long>>().extractData(result)
        }
        every { connection.prepareStatement(any<String>()) } returns statement
        justRun { statement.setLong(any(), any()) }
        justRun { statement.setString(any(), any()) }
        justRun { statement.setTimestamp(any(), any<Timestamp>(), any<Calendar>()) }
        every { result.next() } returns true
        every { result.getLong("comment_id") } returns 13L

        // when
        val created = writer.create(42L, 7L, content)

        // then
        assertEquals(CommentSnapshot(13L, 42L, 7L, content, now, now), created)
        assertFalse(transactions.active)
        assertEquals(1, insertAttempts)
        val sql = slot<String>()
        verify(exactly = 1) { connection.prepareStatement(capture(sql)) }
        assertTrue(sql.captured.contains("INSERT INTO pawbridge_community.comments"))
        assertTrue(sql.captured.contains("RETURNING comment_id"))
        verify(exactly = 1) { statement.setLong(1, 42L) }
        verify(exactly = 1) { statement.setLong(2, 7L) }
        verify(exactly = 1) { statement.setString(3, content) }
        val timestamps = mutableListOf<Timestamp>()
        val calendars = mutableListOf<Calendar>()
        verify(exactly = 1) { statement.setTimestamp(4, capture(timestamps), capture(calendars)) }
        verify(exactly = 1) { statement.setTimestamp(5, capture(timestamps), capture(calendars)) }
        assertEquals(Timestamp.valueOf(now), timestamps[0])
        assertEquals(Timestamp.valueOf(now), timestamps[1])
        assertEquals(123456789, timestamps[0].nanos)
        assertEquals("UTC", calendars[0].timeZone.id)
        assertSame(calendars[0], calendars[1])
        verify(exactly = 1) { jdbc.query(any<PreparedStatementCreator>(), any<ResultSetExtractor<Long>>()) }
        confirmVerified(jdbc)
    }

    @Test
    fun `생성된 식별자가 없을 때__작성하면__재시도 없이 트랜잭션을 벗어나 실패한다`() {
        // given
        every { jdbc.query(any<PreparedStatementCreator>(), any<ResultSetExtractor<Long>>()) } answers {
            assertTrue(transactions.active)
            insertAttempts++
            firstArg<PreparedStatementCreator>().createPreparedStatement(connection)
            secondArg<ResultSetExtractor<Long>>().extractData(result)
        }
        every { connection.prepareStatement(any<String>()) } returns statement
        justRun { statement.setLong(any(), any()) }
        justRun { statement.setString(any(), any()) }
        justRun { statement.setTimestamp(any(), any<Timestamp>(), any<Calendar>()) }
        every { result.next() } returns false

        // when
        assertThrows(IllegalStateException::class.java) { writer.create(42L, 7L, "댓글") }

        // then
        assertEquals(1, insertAttempts)
        assertFalse(transactions.active)
    }

    @Test
    fun `INSERT가 실패할 때__작성하면__한 번 시도한 뒤 트랜잭션을 벗어나 원래 예외를 전파한다`() {
        // given
        val failure = DataAccessResourceFailureException("synthetic unavailable")

        every { jdbc.query(any<PreparedStatementCreator>(), any<ResultSetExtractor<Long>>()) } throws failure

        // when
        val thrown = assertThrows(DataAccessResourceFailureException::class.java) {
            writer.create(42L, 7L, "댓글")
        }

        // then
        assertSame(failure, thrown)
        assertFalse(transactions.active)
        verify(exactly = 1) { jdbc.query(any<PreparedStatementCreator>(), any<ResultSetExtractor<Long>>()) }
        confirmVerified(jdbc)
    }

    @Test
    fun `커밋 시점에 문자셋 제약이 실패할 때__작성하면__이름 붙은 커뮤니티 제약을 사용자 오류로 바꾼다`() {
        // given
        val failure = TransactionSystemException(
            "commit",
            constraint("23514", "pawbridge_community", "ck_rollback_charset_comments"),
        )
        transactions.failureAfterCallback = failure

        every { jdbc.query(any<PreparedStatementCreator>(), any<ResultSetExtractor<Long>>()) } answers {
            insertAttempts++
            firstArg<PreparedStatementCreator>().createPreparedStatement(connection)
            secondArg<ResultSetExtractor<Long>>().extractData(result)
        }
        every { connection.prepareStatement(any<String>()) } returns statement
        justRun { statement.setLong(any(), any()) }
        justRun { statement.setString(any(), any()) }
        justRun { statement.setTimestamp(any(), any<Timestamp>(), any<Calendar>()) }
        every { result.next() } returns true
        every { result.getLong("comment_id") } returns 13L

        // when
        val translated = assertThrows(UnsupportedCommentCharactersException::class.java) {
            writer.create(42L, 7L, "댓글")
        }

        // then
        assertSame(failure, translated.cause)
        assertEquals("일부 이모지 등 지원하지 않는 문자를 제외해 주세요.", translated.message)
        assertEquals(1, insertAttempts)
        assertFalse(transactions.active)
    }

    @Test
    fun `커밋 시점에 무관한 체크 제약이 실패할 때__작성하면__사용자 오류로 바꾸지 않고 원래 예외를 전파한다`() {
        // given
        val failure = TransactionSystemException(
            "commit",
            constraint("23514", "pawbridge_community", "business_constraint"),
        )
        transactions.failureAfterCallback = failure

        every { jdbc.query(any<PreparedStatementCreator>(), any<ResultSetExtractor<Long>>()) } answers {
            insertAttempts++
            firstArg<PreparedStatementCreator>().createPreparedStatement(connection)
            secondArg<ResultSetExtractor<Long>>().extractData(result)
        }
        every { connection.prepareStatement(any<String>()) } returns statement
        justRun { statement.setLong(any(), any()) }
        justRun { statement.setString(any(), any()) }
        justRun { statement.setTimestamp(any(), any<Timestamp>(), any<Calendar>()) }
        every { result.next() } returns true
        every { result.getLong("comment_id") } returns 13L

        // when
        val thrown = assertThrows(TransactionSystemException::class.java) { writer.create(42L, 7L, "댓글") }

        // then
        assertSame(failure, thrown)
        assertEquals(1, insertAttempts)
        assertFalse(transactions.active)
    }

    @Test
    fun `타임스탬프 바인딩이 실패할 때__INSERT를 준비하면__문장을 닫고 예외를 전파한다`() {
        // given
        val failure = IllegalStateException("synthetic binding failure")

        every { jdbc.query(any<PreparedStatementCreator>(), any<ResultSetExtractor<Long>>()) } answers {
            insertAttempts++
            firstArg<PreparedStatementCreator>().createPreparedStatement(connection)
            secondArg<ResultSetExtractor<Long>>().extractData(result)
        }
        every { connection.prepareStatement(any<String>()) } returns statement
        justRun { statement.setLong(any(), any()) }
        justRun { statement.setString(any(), any()) }
        every { statement.setTimestamp(4, any<Timestamp>(), any<Calendar>()) } throws failure
        justRun { statement.close() }

        // when
        val thrown = assertThrows(IllegalStateException::class.java) { writer.create(42L, 7L, "댓글") }

        // then
        assertSame(failure, thrown)
        verify(exactly = 1) { statement.close() }
        assertEquals(1, insertAttempts)
        assertFalse(transactions.active)
    }

    private fun constraint(state: String, schema: String, name: String): PSQLException =
        PSQLException(
            ServerErrorMessage(
                "SERROR\u0000C" + state + "\u0000Msynthetic\u0000s" + schema + "\u0000n" + name + "\u0000",
            ),
        )

    /** An in-memory TransactionOperations that records whether a callback is running and can fail after it. */
    private class RecordingTransactions : TransactionOperations {
        var active = false
        var failureAfterCallback: RuntimeException? = null

        override fun <T> execute(action: TransactionCallback<T>): T? {
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
}
