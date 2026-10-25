package com.pawbridge.backend.community.internal.comment.infrastructure

import com.pawbridge.backend.community.internal.comment.application.CommentSnapshot
import com.pawbridge.backend.community.internal.comment.application.UnsupportedCommentCharactersException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when` as whenever
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
    private val jdbc = mock(JdbcOperations::class.java)
    private val transactions = mock(TransactionOperations::class.java)
    private val connection = mock(Connection::class.java)
    private val statement = mock(PreparedStatement::class.java)
    private val result = mock(ResultSet::class.java)
    private val clock = Clock.fixed(Instant.parse("2026-10-19T03:30:10.123456789Z"), ZoneId.of("Asia/Seoul"))
    private val writer = JdbcCommentWriter(jdbc, transactions, clock)
    private var transactionActive = false
    private var insertAttempts = 0

    @BeforeEach
    fun prepareTransactionAndJdbc() {
        whenever(transactions.execute(any<TransactionCallback<CommentSnapshot>>())).thenAnswer { invocation ->
            transactionActive = true
            try {
                invocation.getArgument<TransactionCallback<*>>(0).doInTransaction(SimpleTransactionStatus())
            } finally {
                transactionActive = false
            }
        }
        whenever(connection.prepareStatement(anyString())).thenReturn(statement)
        whenever(result.next()).thenReturn(true)
        whenever(result.getLong("comment_id")).thenReturn(13L)
        whenever(jdbc.query(any(PreparedStatementCreator::class.java), any<ResultSetExtractor<Long>>()))
            .thenAnswer { invocation ->
                assertTrue(transactionActive)
                insertAttempts++
                invocation.getArgument<PreparedStatementCreator>(0).createPreparedStatement(connection)
                invocation.getArgument<ResultSetExtractor<*>>(1).extractData(result)
            }
    }

    @Test
    fun given__comment_content_and_clock__when__create__then__one_comment_insert_returns_identity_and_full_precision_snapshot() {
        val content = "  댓글 원문\n "
        val now = LocalDateTime.now(clock)

        assertEquals(CommentSnapshot(13L, 42L, 7L, content, now, now), writer.create(42L, 7L, content))
        assertFalse(transactionActive)
        assertEquals(1, insertAttempts)
        val sql = ArgumentCaptor.forClass(String::class.java)
        verify(connection).prepareStatement(sql.capture())
        assertTrue(sql.value.contains("INSERT INTO pawbridge_community.comments"))
        assertTrue(sql.value.contains("RETURNING comment_id"))
        verify(statement).setLong(1, 42L)
        verify(statement).setLong(2, 7L)
        verify(statement).setString(3, content)
        val timestamps = ArgumentCaptor.forClass(Timestamp::class.java)
        val calendars = ArgumentCaptor.forClass(Calendar::class.java)
        verify(statement).setTimestamp(eq(4), timestamps.capture(), calendars.capture())
        verify(statement).setTimestamp(eq(5), timestamps.capture(), calendars.capture())
        assertEquals(Timestamp.valueOf(now), timestamps.allValues[0])
        assertEquals(Timestamp.valueOf(now), timestamps.allValues[1])
        assertEquals(123456789, timestamps.allValues[0].nanos)
        assertEquals("UTC", calendars.allValues[0].timeZone.id)
        assertSame(calendars.allValues[0], calendars.allValues[1])
        verify(jdbc).query(any(PreparedStatementCreator::class.java), any<ResultSetExtractor<Long>>())
        verifyNoMoreInteractions(jdbc)
    }

    @Test
    fun given__missing_generated_identity__when__create__then__failure_leaves_transaction_without_retry() {
        whenever(result.next()).thenReturn(false)

        assertThrows(IllegalStateException::class.java) { writer.create(42L, 7L, "댓글") }
        assertEquals(1, insertAttempts)
        assertFalse(transactionActive)
    }

    @Test
    fun given__insert_failure__when__create__then__original_failure_propagates_after_one_attempt_and_transaction_exit() {
        val failure = DataAccessResourceFailureException("synthetic unavailable")
        doThrow(failure).`when`(jdbc)
            .query(any(PreparedStatementCreator::class.java), any<ResultSetExtractor<Long>>())

        assertSame(
            failure,
            assertThrows(DataAccessResourceFailureException::class.java) { writer.create(42L, 7L, "댓글") },
        )
        assertFalse(transactionActive)
        verify(jdbc, times(1)).query(any(PreparedStatementCreator::class.java), any<ResultSetExtractor<Long>>())
        verifyNoMoreInteractions(jdbc)
    }

    @Test
    fun given__charset_failure_at_commit__when__create__then__named_community_constraint_becomes_user_error() {
        val failure = TransactionSystemException(
            "commit",
            constraint("23514", "pawbridge_community", "ck_rollback_charset_comments"),
        )
        failAfterCallback(failure)

        val translated = assertThrows(UnsupportedCommentCharactersException::class.java) {
            writer.create(42L, 7L, "댓글")
        }
        assertSame(failure, translated.cause)
        assertEquals("일부 이모지 등 지원하지 않는 문자를 제외해 주세요.", translated.message)
        assertEquals(1, insertAttempts)
        assertFalse(transactionActive)
    }

    @Test
    fun given__unrelated_check_failure_at_commit__when__create__then__original_failure_is_not_a_user_error() {
        val failure = TransactionSystemException(
            "commit",
            constraint("23514", "pawbridge_community", "business_constraint"),
        )
        failAfterCallback(failure)

        assertSame(
            failure,
            assertThrows(TransactionSystemException::class.java) { writer.create(42L, 7L, "댓글") },
        )
        assertEquals(1, insertAttempts)
        assertFalse(transactionActive)
    }

    @Test
    fun given__timestamp_binding_failure__when__preparing_insert__then__statement_is_closed_and_failure_propagates() {
        val failure = IllegalStateException("synthetic binding failure")
        doThrow(failure).`when`(statement).setTimestamp(eq(4), any(Timestamp::class.java), any(Calendar::class.java))

        assertSame(failure, assertThrows(IllegalStateException::class.java) { writer.create(42L, 7L, "댓글") })
        verify(statement).close()
        assertEquals(1, insertAttempts)
        assertFalse(transactionActive)
    }

    private fun failAfterCallback(failure: RuntimeException) {
        doAnswer { invocation ->
            transactionActive = true
            try {
                invocation.getArgument<TransactionCallback<*>>(0).doInTransaction(SimpleTransactionStatus())
                throw failure
            } finally {
                transactionActive = false
            }
        }.`when`(transactions).execute(any<TransactionCallback<CommentSnapshot>>())
    }

    private fun constraint(state: String, schema: String, name: String): PSQLException =
        PSQLException(
            ServerErrorMessage(
                "SERROR\u0000C" + state + "\u0000Msynthetic\u0000s" + schema + "\u0000n" + name + "\u0000",
            ),
        )
}
