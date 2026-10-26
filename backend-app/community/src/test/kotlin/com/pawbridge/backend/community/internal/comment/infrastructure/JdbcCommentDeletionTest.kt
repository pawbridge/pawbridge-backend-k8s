package com.pawbridge.backend.community.internal.comment.infrastructure

import com.pawbridge.backend.community.internal.comment.application.CommentNotFoundException
import com.pawbridge.backend.community.internal.comment.application.UnsupportedCommentCharactersException
import com.pawbridge.backend.community.internal.comment.domain.CommentNotOwnedException
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
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
import org.junit.jupiter.params.provider.ValueSource
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
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Calendar
import java.util.stream.Stream

class JdbcCommentDeletionTest {
    private val jdbc = mockk<JdbcOperations>()
    private val transactions = RecordingTransactions()
    private val connection = mockk<Connection>()
    private val result = mockk<ResultSet>()
    private val clock = mockk<Clock>()
    private val deletion = JdbcCommentDeletion(jdbc, transactions, clock)
    private val calls = ArrayList<StatementCall>()
    private val failure: RuntimeException = DataAccessResourceFailureException("synthetic SQL failure")

    @Test
    fun `본인의 활성 댓글일 때__삭제하면__한 트랜잭션에서 일반 조회와 서로 다른 두 시각의 UPDATE를 실행한다`() {
        // given
        every { clock.zone } returns ZONE
        every { clock.instant() } returnsMany listOf(DELETED, UPDATED)
        every { connection.prepareStatement(any<String>()) } answers {
            assertTrue(transactions.active)
            val call = StatementCall(firstArg<String>())
            calls.add(call)
            call.statement
        }
        every { result.next() } returns true
        every { result.getLong("author_id") } returns 7L
        every { jdbc.query(any<PreparedStatementCreator>(), any<ResultSetExtractor<Long>>()) } answers {
            firstArg<PreparedStatementCreator>().createPreparedStatement(connection)
            secondArg<ResultSetExtractor<Long>>().extractData(result)
        }
        every { jdbc.update(any<PreparedStatementCreator>()) } answers {
            firstArg<PreparedStatementCreator>().createPreparedStatement(connection)
            1
        }

        // when
        deletion.delete(42L, 7L)

        // then
        assertFalse(transactions.active)
        assertEquals(2, calls.size)
        val lookup = calls.first()
        val lookupSql = lookup.sql.replace(Regex("\\s+"), " ").trim()
        assertEquals(
            "SELECT author_id FROM pawbridge_community.comments WHERE comment_id = ? AND deleted_at IS NULL",
            lookupSql,
        )
        assertEquals(mapOf(1 to 42L), lookup.values)
        val update = calls.last()
        val updateSql = update.sql.replace(Regex("\\s+"), " ").trim()
        assertEquals(
            "UPDATE pawbridge_community.comments SET deleted_at = ?, updated_at = ? WHERE comment_id = ?",
            updateSql,
        )
        assertEquals(
            mapOf(
                1 to Timestamp.valueOf(LocalDateTime.ofInstant(DELETED, ZONE)),
                2 to Timestamp.valueOf(LocalDateTime.ofInstant(UPDATED, ZONE)),
                3 to 42L,
            ),
            update.values,
        )
        assertEquals("UTC", update.calendars[1]!!.timeZone.id)
        assertEquals("UTC", update.calendars[2]!!.timeZone.id)
        verify(exactly = 2) { clock.instant() }
        assertEquals(1, transactions.executions)
        verify(exactly = 1) { jdbc.query(any<PreparedStatementCreator>(), any<ResultSetExtractor<Long>>()) }
        verify(exactly = 1) { jdbc.update(any<PreparedStatementCreator>()) }
        confirmVerified(jdbc)
    }

    @Test
    fun `없거나 삭제된 댓글일 때__다른 작성자가 삭제하면__소유권 확인과 UPDATE보다 먼저 찾을 수 없음으로 실패한다`() {
        // given
        every { connection.prepareStatement(any<String>()) } answers {
            val call = StatementCall(firstArg<String>())
            calls.add(call)
            call.statement
        }
        every { result.next() } returns false
        every { jdbc.query(any<PreparedStatementCreator>(), any<ResultSetExtractor<Long>>()) } answers {
            firstArg<PreparedStatementCreator>().createPreparedStatement(connection)
            secondArg<ResultSetExtractor<Long>>().extractData(result)
        }

        // when
        val rejected = assertThrows(CommentNotFoundException::class.java) { deletion.delete(42L, 99L) }

        // then
        assertEquals("댓글을 찾을 수 없습니다.", rejected.message)
        assertEquals(1, calls.size)
        assertFalse(transactions.active)
        verify(exactly = 1) { jdbc.query(any<PreparedStatementCreator>(), any<ResultSetExtractor<Long>>()) }
        verify(exactly = 0) { jdbc.update(any<PreparedStatementCreator>()) }
        confirmVerified(jdbc, clock)
    }

    @Test
    fun `작성자가 다를 때__삭제하면__시계 조회와 UPDATE보다 먼저 소유권으로 거부한다`() {
        // given
        every { connection.prepareStatement(any<String>()) } answers {
            val call = StatementCall(firstArg<String>())
            calls.add(call)
            call.statement
        }
        every { result.next() } returns true
        every { result.getLong("author_id") } returns 8L
        every { jdbc.query(any<PreparedStatementCreator>(), any<ResultSetExtractor<Long>>()) } answers {
            firstArg<PreparedStatementCreator>().createPreparedStatement(connection)
            secondArg<ResultSetExtractor<Long>>().extractData(result)
        }

        // when
        assertThrows(CommentNotOwnedException::class.java) { deletion.delete(42L, 7L) }

        // then
        assertEquals(1, calls.size)
        assertFalse(transactions.active)
        verify(exactly = 1) { jdbc.query(any<PreparedStatementCreator>(), any<ResultSetExtractor<Long>>()) }
        verify(exactly = 0) { jdbc.update(any<PreparedStatementCreator>()) }
        confirmVerified(jdbc, clock)
    }

    @Test
    fun `조회 뒤 행이 사라졌을 때__삭제하면__찾을 수 없음이 아닌 예상 밖 실패로 처리하고 재시도하지 않는다`() {
        // given
        every { clock.zone } returns ZONE
        every { clock.instant() } returnsMany listOf(DELETED, UPDATED)
        every { connection.prepareStatement(any<String>()) } answers {
            val call = StatementCall(firstArg<String>())
            calls.add(call)
            call.statement
        }
        every { result.next() } returns true
        every { result.getLong("author_id") } returns 7L
        every { jdbc.query(any<PreparedStatementCreator>(), any<ResultSetExtractor<Long>>()) } answers {
            firstArg<PreparedStatementCreator>().createPreparedStatement(connection)
            secondArg<ResultSetExtractor<Long>>().extractData(result)
        }
        every { jdbc.update(any<PreparedStatementCreator>()) } answers {
            firstArg<PreparedStatementCreator>().createPreparedStatement(connection)
            0
        }

        // when
        assertThrows(IllegalStateException::class.java) { deletion.delete(42L, 7L) }

        // then
        assertEquals(2, calls.size)
        assertFalse(transactions.active)
        verify(exactly = 1) { jdbc.update(any<PreparedStatementCreator>()) }
        assertEquals(1, transactions.executions)
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 2])
    fun `조회나 UPDATE가 실패할 때__삭제하면__재시도 없이 트랜잭션을 벗어나 원래 예외를 전파한다`(
        statement: Int,
    ) {
        // given
        every { clock.zone } returns ZONE
        every { clock.instant() } returnsMany listOf(DELETED, UPDATED)
        every { connection.prepareStatement(any<String>()) } answers {
            val call = StatementCall(firstArg<String>())
            calls.add(call)
            call.statement
        }
        every { result.next() } returns true
        every { result.getLong("author_id") } returns 7L
        every { jdbc.query(any<PreparedStatementCreator>(), any<ResultSetExtractor<Long>>()) } answers {
            firstArg<PreparedStatementCreator>().createPreparedStatement(connection)
            if (statement == calls.size) throw failure
            secondArg<ResultSetExtractor<Long>>().extractData(result)
        }
        every { jdbc.update(any<PreparedStatementCreator>()) } answers {
            firstArg<PreparedStatementCreator>().createPreparedStatement(connection)
            if (statement == calls.size) throw failure
            1
        }

        // when
        val thrown = assertThrows(DataAccessResourceFailureException::class.java) { deletion.delete(42L, 7L) }

        // then
        assertSame(failure, thrown)
        assertEquals(statement, calls.size)
        assertFalse(transactions.active)
        assertEquals(1, transactions.executions)
    }

    @ParameterizedTest
    @MethodSource("commitFailures")
    fun `UPDATE 뒤 커밋이 실패할 때__삭제하면__이름 붙은 문자셋 오류만 사용자 오류로 바꾼다`(
        commitFailure: TransactionSystemException,
        translated: Boolean,
    ) {
        // given
        transactions.failureAfterCallback = commitFailure

        every { clock.zone } returns ZONE
        every { clock.instant() } returnsMany listOf(DELETED, UPDATED)
        every { connection.prepareStatement(any<String>()) } answers {
            val call = StatementCall(firstArg<String>())
            calls.add(call)
            call.statement
        }
        every { result.next() } returns true
        every { result.getLong("author_id") } returns 7L
        every { jdbc.query(any<PreparedStatementCreator>(), any<ResultSetExtractor<Long>>()) } answers {
            firstArg<PreparedStatementCreator>().createPreparedStatement(connection)
            secondArg<ResultSetExtractor<Long>>().extractData(result)
        }
        every { jdbc.update(any<PreparedStatementCreator>()) } answers {
            firstArg<PreparedStatementCreator>().createPreparedStatement(connection)
            1
        }

        // when
        val rejected = assertThrows(RuntimeException::class.java) { deletion.delete(42L, 7L) }

        // then
        if (translated) {
            assertTrue(rejected is UnsupportedCommentCharactersException)
            assertSame(commitFailure, rejected.cause)
            assertEquals("일부 이모지 등 지원하지 않는 문자를 제외해 주세요.", rejected.message)
        } else {
            assertSame(commitFailure, rejected)
        }
        assertEquals(2, calls.size)
        assertFalse(transactions.active)
        assertEquals(1, transactions.executions)
    }

    @Test
    fun `타임스탬프 바인딩이 실패할 때__UPDATE를 준비하면__문장을 닫은 뒤 실패를 전파한다`() {
        // given
        every { clock.zone } returns ZONE
        every { clock.instant() } returnsMany listOf(DELETED, UPDATED)
        every { connection.prepareStatement(any<String>()) } answers {
            val call = StatementCall(firstArg<String>(), if (calls.size == 1) failure else null)
            calls.add(call)
            call.statement
        }
        every { result.next() } returns true
        every { result.getLong("author_id") } returns 7L
        every { jdbc.query(any<PreparedStatementCreator>(), any<ResultSetExtractor<Long>>()) } answers {
            firstArg<PreparedStatementCreator>().createPreparedStatement(connection)
            secondArg<ResultSetExtractor<Long>>().extractData(result)
        }
        every { jdbc.update(any<PreparedStatementCreator>()) } answers {
            firstArg<PreparedStatementCreator>().createPreparedStatement(connection)
            1
        }

        // when
        val thrown = assertThrows(DataAccessResourceFailureException::class.java) { deletion.delete(42L, 7L) }

        // then
        assertSame(failure, thrown)
        assertEquals(2, calls.size)
        assertEquals(1, calls.last().closed)
        assertFalse(transactions.active)
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

    /**
     * An in-memory PreparedStatement that records bound values.
     * PreparedStatement has many overloads of setObject and setTimestamp, so a small Proxy is simpler than a strict mock.
     */
    private class StatementCall(
        val sql: String,
        private val timestampFailure: RuntimeException? = null,
    ) {
        val values = LinkedHashMap<Int, Any?>()
        val calendars = LinkedHashMap<Int, Calendar>()
        var closed = 0

        val statement: PreparedStatement = Proxy.newProxyInstance(
            PreparedStatement::class.java.classLoader,
            arrayOf(PreparedStatement::class.java),
        ) { _, method, arguments ->
            when (method.name) {
                "setObject" -> {
                    values[arguments[0] as Int] = arguments[1]
                    null
                }
                "setTimestamp" -> {
                    if (timestampFailure != null) {
                        throw timestampFailure
                    }
                    values[arguments[0] as Int] = arguments[1]
                    calendars[arguments[0] as Int] = arguments[2] as Calendar
                    null
                }
                "close" -> {
                    closed++
                    null
                }
                else -> throw UnsupportedOperationException(method.name)
            }
        } as PreparedStatement
    }

    companion object {
        private val ZONE = ZoneId.of("Asia/Seoul")
        private val DELETED = Instant.parse("2026-10-19T03:30:10.123456789Z")
        private val UPDATED = Instant.parse("2026-10-19T03:30:11.654321987Z")

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
