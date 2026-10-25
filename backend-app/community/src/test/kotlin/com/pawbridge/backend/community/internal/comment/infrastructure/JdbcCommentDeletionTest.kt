package com.pawbridge.backend.community.internal.comment.infrastructure

import com.pawbridge.backend.community.internal.comment.application.CommentNotFoundException
import com.pawbridge.backend.community.internal.comment.application.UnsupportedCommentCharactersException
import com.pawbridge.backend.community.internal.comment.domain.CommentNotOwnedException
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
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
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
import java.util.stream.Stream

class JdbcCommentDeletionTest {
    private val jdbc = mock(JdbcOperations::class.java)
    private val transactions = mock(TransactionOperations::class.java)
    private val connection = mock(Connection::class.java)
    private val result = mock(ResultSet::class.java)
    private val clock = mock(Clock::class.java)
    private val deletion = JdbcCommentDeletion(jdbc, transactions, clock)
    private val calls = ArrayList<StatementCall>()
    private val failure: RuntimeException = DataAccessResourceFailureException("synthetic SQL failure")
    private var transactionActive = false
    private var failAt = 0
    private var updateCount = 1
    private var failTimestampBinding = false

    @BeforeEach
    fun prepareTransactionAndJdbc() {
        whenever(clock.zone).thenReturn(ZONE)
        whenever(clock.instant()).thenReturn(DELETED, UPDATED)
        whenever(transactions.execute(any<TransactionCallback<Void>>())).thenAnswer { invocation ->
            transactionActive = true
            try {
                invocation.getArgument<TransactionCallback<*>>(0).doInTransaction(SimpleTransactionStatus())
            } finally {
                transactionActive = false
            }
        }
        whenever(connection.prepareStatement(anyString())).thenAnswer { invocation ->
            assertTrue(transactionActive)
            val statement = mock(PreparedStatement::class.java)
            val call = StatementCall(invocation.getArgument(0), statement)
            calls.add(call)
            doAnswer { binding ->
                call.values[binding.getArgument(0)] = binding.getArgument(1)
                null
            }.`when`(statement).setObject(anyInt(), any())
            doAnswer { binding ->
                call.values[binding.getArgument(0)] = binding.getArgument(1)
                call.calendars[binding.getArgument(0)] = binding.getArgument(2)
                null
            }.`when`(statement).setTimestamp(anyInt(), any(Timestamp::class.java), any(Calendar::class.java))
            if (failTimestampBinding && calls.size == 2) {
                doThrow(failure).`when`(statement)
                    .setTimestamp(eq(1), any(Timestamp::class.java), any(Calendar::class.java))
            }
            statement
        }
        whenever(result.next()).thenReturn(true)
        whenever(result.getLong("author_id")).thenReturn(7L)
        whenever(jdbc.query(any(PreparedStatementCreator::class.java), any<ResultSetExtractor<Long>>()))
            .thenAnswer { invocation ->
                invocation.getArgument<PreparedStatementCreator>(0).createPreparedStatement(connection)
                if (failAt == calls.size) throw failure
                invocation.getArgument<ResultSetExtractor<*>>(1).extractData(result)
            }
        whenever(jdbc.update(any(PreparedStatementCreator::class.java))).thenAnswer { invocation ->
            invocation.getArgument<PreparedStatementCreator>(0).createPreparedStatement(connection)
            if (failAt == calls.size) throw failure
            updateCount
        }
    }

    @Test
    fun given__own_active_comment__when__delete__then__ordinary_lookup_and_two_distinct_dates_use_one_transaction() {
        deletion.delete(42L, 7L)

        assertFalse(transactionActive)
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
        verify(clock, times(2)).instant()
        verify(transactions).execute(any<TransactionCallback<Void>>())
        verify(jdbc).query(any(PreparedStatementCreator::class.java), any<ResultSetExtractor<Long>>())
        verify(jdbc).update(any(PreparedStatementCreator::class.java))
        verifyNoMoreInteractions(jdbc)
    }

    @Test
    fun given__missing_or_deleted_comment__when__delete_by_another_author__then__not_found_precedes_ownership_and_update() {
        whenever(result.next()).thenReturn(false)

        val rejected = assertThrows(CommentNotFoundException::class.java) { deletion.delete(42L, 99L) }
        assertEquals("댓글을 찾을 수 없습니다.", rejected.message)
        assertEquals(1, calls.size)
        assertFalse(transactionActive)
        verify(jdbc, never()).update(any(PreparedStatementCreator::class.java))
        verifyNoInteractions(clock)
    }

    @Test
    fun given__different_author__when__delete__then__ownership_rejection_precedes_clock_and_update() {
        whenever(result.getLong("author_id")).thenReturn(8L)

        assertThrows(CommentNotOwnedException::class.java) { deletion.delete(42L, 7L) }
        assertEquals(1, calls.size)
        assertFalse(transactionActive)
        verify(jdbc, never()).update(any(PreparedStatementCreator::class.java))
        verifyNoInteractions(clock)
    }

    @Test
    fun given__row_disappears_after_lookup__when__delete__then__unexpected_failure_is_not_not_found_or_retried() {
        updateCount = 0

        assertThrows(IllegalStateException::class.java) { deletion.delete(42L, 7L) }
        assertEquals(2, calls.size)
        assertFalse(transactionActive)
        verify(jdbc, times(1)).update(any(PreparedStatementCreator::class.java))
        verify(transactions, times(1)).execute(any<TransactionCallback<Void>>())
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 2])
    fun given__lookup_or_update_failure__when__delete__then__original_failure_leaves_transaction_without_retry(
        statement: Int,
    ) {
        failAt = statement

        assertSame(
            failure,
            assertThrows(DataAccessResourceFailureException::class.java) { deletion.delete(42L, 7L) },
        )
        assertEquals(statement, calls.size)
        assertFalse(transactionActive)
        verify(transactions, times(1)).execute(any<TransactionCallback<Void>>())
    }

    @ParameterizedTest
    @MethodSource("commitFailures")
    fun given__commit_failure_after_update__when__delete__then__only_named_charset_error_is_translated(
        commitFailure: TransactionSystemException,
        translated: Boolean,
    ) {
        failAfterCallback(commitFailure)

        if (translated) {
            val rejected = assertThrows(UnsupportedCommentCharactersException::class.java) {
                deletion.delete(42L, 7L)
            }
            assertSame(commitFailure, rejected.cause)
            assertEquals("일부 이모지 등 지원하지 않는 문자를 제외해 주세요.", rejected.message)
        } else {
            assertSame(
                commitFailure,
                assertThrows(TransactionSystemException::class.java) { deletion.delete(42L, 7L) },
            )
        }
        assertEquals(2, calls.size)
        assertFalse(transactionActive)
        verify(transactions, times(1)).execute(any<TransactionCallback<Void>>())
    }

    @Test
    fun given__timestamp_binding_failure__when__preparing_update__then__close_statement_before_propagating_failure() {
        failTimestampBinding = true

        assertSame(
            failure,
            assertThrows(DataAccessResourceFailureException::class.java) { deletion.delete(42L, 7L) },
        )
        assertEquals(2, calls.size)
        verify(calls.last().statement).close()
        assertFalse(transactionActive)
    }

    private fun failAfterCallback(commitFailure: RuntimeException) {
        doAnswer { invocation ->
            transactionActive = true
            try {
                invocation.getArgument<TransactionCallback<*>>(0).doInTransaction(SimpleTransactionStatus())
                throw commitFailure
            } finally {
                transactionActive = false
            }
        }.`when`(transactions).execute(any<TransactionCallback<Void>>())
    }

    private class StatementCall(
        val sql: String,
        val statement: PreparedStatement,
    ) {
        val values = LinkedHashMap<Int, Any?>()
        val calendars = LinkedHashMap<Int, Calendar>()
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
