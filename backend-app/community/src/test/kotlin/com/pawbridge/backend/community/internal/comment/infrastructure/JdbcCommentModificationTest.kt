package com.pawbridge.backend.community.internal.comment.infrastructure

import com.pawbridge.backend.community.internal.comment.application.UnsupportedCommentCharactersException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
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
    private val jdbc = mock(JdbcOperations::class.java)
    private val transactions = mock(TransactionOperations::class.java)
    private val connection = mock(Connection::class.java)
    private val statement = mock(PreparedStatement::class.java)
    private val clock = mock(Clock::class.java)
    private val writer = JdbcCommentWriter(jdbc, transactions, clock)
    private var transactionActive = false
    private var updateCount = 1
    private var updateAttempts = 0

    @BeforeEach
    fun prepareTransactionAndJdbc() {
        whenever(clock.zone).thenReturn(ZONE)
        whenever(clock.instant()).thenAnswer {
            assertTrue(transactionActive)
            UPDATED
        }
        whenever(transactions.execute(any<TransactionCallback<Void>>())).thenAnswer { invocation ->
            transactionActive = true
            try {
                invocation.getArgument<TransactionCallback<*>>(0).doInTransaction(SimpleTransactionStatus())
            } finally {
                transactionActive = false
            }
        }
        whenever(connection.prepareStatement(anyString())).thenAnswer {
            assertTrue(transactionActive)
            statement
        }
        whenever(jdbc.update(any(PreparedStatementCreator::class.java))).thenAnswer { invocation ->
            updateAttempts++
            invocation.getArgument<PreparedStatementCreator>(0).createPreparedStatement(connection)
            updateCount
        }
    }

    @Test
    fun given__changed_raw_content__when__update__then__one_id_only_update_binds_content_and_audit_time_without_reads() {
        val content = "  수정 원문\n　 "

        writer.update(13L, content)

        assertFalse(transactionActive)
        assertEquals(1, updateAttempts)
        val sql = ArgumentCaptor.forClass(String::class.java)
        verify(connection).prepareStatement(sql.capture())
        assertEquals(
            "UPDATE pawbridge_community.comments SET content = ?, updated_at = ? WHERE comment_id = ?",
            sql.value.replace(Regex("\\s+"), " ").trim(),
        )
        verify(statement).setString(1, content)
        val timestamp = ArgumentCaptor.forClass(Timestamp::class.java)
        val calendar = ArgumentCaptor.forClass(Calendar::class.java)
        verify(statement).setTimestamp(eq(2), timestamp.capture(), calendar.capture())
        assertEquals(Timestamp.valueOf(LocalDateTime.ofInstant(UPDATED, ZONE)), timestamp.value)
        assertEquals(123456789, timestamp.value.nanos)
        assertEquals("UTC", calendar.value.timeZone.id)
        verify(statement).setLong(3, 13L)
        verify(clock).instant()
        verify(transactions).execute(any<TransactionCallback<Void>>())
        verify(jdbc).update(any(PreparedStatementCreator::class.java))
        verifyNoMoreInteractions(jdbc)
    }

    @Test
    fun given__row_disappeared_after_snapshot__when__update__then__zero_rows_is_unexpected_failure_without_retry() {
        updateCount = 0

        assertThrows(IllegalStateException::class.java) { writer.update(13L, "댓글") }
        assertFalse(transactionActive)
        assertEquals(1, updateAttempts)
        verify(transactions).execute(any<TransactionCallback<Void>>())
        verify(jdbc).update(any(PreparedStatementCreator::class.java))
        verifyNoMoreInteractions(jdbc)
    }

    @Test
    fun given__update_sql_failure__when__update__then__original_failure_leaves_transaction_without_retry() {
        val failure = DataAccessResourceFailureException("synthetic unavailable")
        doThrow(failure).`when`(jdbc).update(any(PreparedStatementCreator::class.java))

        assertSame(
            failure,
            assertThrows(DataAccessResourceFailureException::class.java) { writer.update(13L, "댓글") },
        )
        assertFalse(transactionActive)
        verify(transactions).execute(any<TransactionCallback<Void>>())
        verify(jdbc).update(any(PreparedStatementCreator::class.java))
        verifyNoMoreInteractions(jdbc)
    }

    @ParameterizedTest
    @MethodSource("commitFailures")
    fun given__commit_failure_after_update__when__update__then__only_named_community_charset_rejection_is_translated(
        commitFailure: TransactionSystemException,
        translated: Boolean,
    ) {
        doAnswer { invocation ->
            transactionActive = true
            try {
                invocation.getArgument<TransactionCallback<*>>(0).doInTransaction(SimpleTransactionStatus())
                throw commitFailure
            } finally {
                transactionActive = false
            }
        }.`when`(transactions).execute(any<TransactionCallback<Void>>())

        if (translated) {
            val failure = assertThrows(UnsupportedCommentCharactersException::class.java) {
                writer.update(13L, "댓글")
            }
            assertSame(commitFailure, failure.cause)
            assertEquals("일부 이모지 등 지원하지 않는 문자를 제외해 주세요.", failure.message)
        } else {
            assertSame(
                commitFailure,
                assertThrows(TransactionSystemException::class.java) { writer.update(13L, "댓글") },
            )
        }
        assertFalse(transactionActive)
        assertEquals(1, updateAttempts)
        verify(transactions, times(1)).execute(any<TransactionCallback<Void>>())
        verify(jdbc, times(1)).update(any(PreparedStatementCreator::class.java))
        verifyNoMoreInteractions(jdbc)
    }

    @Test
    fun given__timestamp_binding_and_close_failures__when__preparing_update__then__statement_is_closed_and_original_failure_retained() {
        val failure = IllegalStateException("synthetic binding failure")
        val closing = SQLException("synthetic close failure")
        doThrow(failure).`when`(statement)
            .setTimestamp(eq(2), any(Timestamp::class.java), any(Calendar::class.java))
        doThrow(closing).`when`(statement).close()

        assertSame(failure, assertThrows(IllegalStateException::class.java) { writer.update(13L, "댓글") })
        assertEquals(1, updateAttempts)
        assertFalse(transactionActive)
        assertEquals(1, failure.suppressed.size)
        assertSame(closing, failure.suppressed[0])
        verify(statement).close()
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
