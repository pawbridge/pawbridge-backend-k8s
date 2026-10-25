package com.pawbridge.backend.community.internal.post.infrastructure

import com.pawbridge.backend.community.internal.post.application.PostNotFoundException
import com.pawbridge.backend.community.internal.post.domain.PostNotOwnedException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
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

class JdbcPostDeletionTest {
    private val jdbc = mock(JdbcOperations::class.java)
    private val transactions = mock(TransactionOperations::class.java)
    private val connection = mock(Connection::class.java)
    private val result = mock(ResultSet::class.java)
    private val clock = Clock.fixed(Instant.parse("2026-10-19T00:05:10.123456789Z"), ZoneId.of("Asia/Seoul"))
    private val calls = ArrayList<StatementCall>()
    private var transactionActive = false
    private var failAt = 0
    private var updateCount = 1
    private val failure: RuntimeException = IllegalStateException("synthetic DB failure")

    @BeforeEach
    fun prepareTransactionAndStatements() {
        whenever(transactions.execute(any<TransactionCallback<Void>>())).thenAnswer { call ->
            val callback = call.getArgument<TransactionCallback<Void>>(0)
            transactionActive = true
            try {
                callback.doInTransaction(SimpleTransactionStatus())
            } finally {
                transactionActive = false
            }
        }
        whenever(connection.prepareStatement(anyString())).thenAnswer { call ->
            assertTrue(transactionActive)
            val statementCall = StatementCall(call.getArgument(0))
            calls.add(statementCall)
            val statement = mock(PreparedStatement::class.java)
            doAnswer { binding ->
                statementCall.values[binding.getArgument(0)] = binding.getArgument(1)
                null
            }.`when`(statement).setObject(anyInt(), any())
            doAnswer { binding ->
                statementCall.values[binding.getArgument(0)] = binding.getArgument(1)
                statementCall.calendars[binding.getArgument(0)] = binding.getArgument(2)
                null
            }.`when`(statement).setTimestamp(anyInt(), any(Timestamp::class.java), any(Calendar::class.java))
            statement
        }
        whenever(result.next()).thenReturn(true)
        whenever(result.getLong("author_id")).thenReturn(7L)
        whenever(jdbc.query(any(PreparedStatementCreator::class.java), any<ResultSetExtractor<Long>>()))
            .thenAnswer { call ->
                call.getArgument<PreparedStatementCreator>(0).createPreparedStatement(connection)
                if (failAt == calls.size) throw failure
                call.getArgument<ResultSetExtractor<*>>(1).extractData(result)
            }
        whenever(jdbc.update(any(PreparedStatementCreator::class.java))).thenAnswer { call ->
            call.getArgument<PreparedStatementCreator>(0).createPreparedStatement(connection)
            if (failAt == calls.size) throw failure
            updateCount
        }
    }

    @Test
    fun given__own_active_post__when__delete__then__lock_and_two_mutations_share_transaction_without_outbox() {
        deletion().delete(42L, 7L)

        assertFalse(transactionActive)
        assertEquals(3, calls.size)
        assertTrue(calls.stream().noneMatch { call -> call.sql.contains("outbox_events") })
        val lock = calls[0]
        assertTrue(lock.sql.contains("pawbridge_community.posts"))
        assertTrue(lock.sql.contains("deleted_at IS NULL"))
        assertTrue(lock.sql.contains("FOR UPDATE"))
        assertEquals(42L, lock.values[1])
        val source = calls[1]
        assertTrue(source.sql.contains("SET deleted_at = ?, updated_at = ?"))
        assertTrue(source.sql.contains("post_id = ? AND author_id = ? AND deleted_at IS NULL"))
        assertFalse(source.sql.contains("title ="))
        assertFalse(source.sql.contains("image_urls ="))
        assertEquals(Timestamp.valueOf(LocalDateTime.now(clock)), source.values[1])
        assertEquals(Timestamp.valueOf(LocalDateTime.now(clock)), source.values[2])
        assertEquals(42L, source.values[3])
        assertEquals(7L, source.values[4])
        assertEquals("UTC", source.calendars[1]!!.timeZone.id)
        assertEquals("UTC", source.calendars[2]!!.timeZone.id)
        assertTrue(calls[2].sql.contains("DELETE FROM pawbridge_community.post_search_documents WHERE post_id = ?"))
        assertEquals(mapOf(1 to 42L), calls[2].values)
    }

    @Test
    fun given__missing_or_already_deleted_locked_row__when__delete__then__no_mutation_is_attempted() {
        whenever(result.next()).thenReturn(false)

        assertThrows(PostNotFoundException::class.java) { deletion().delete(42L, 7L) }

        assertEquals(1, calls.size)
        verify(jdbc, never()).update(any(PreparedStatementCreator::class.java))
    }

    @Test
    fun given__different_locked_owner__when__delete__then__no_mutation_is_attempted() {
        whenever(result.getLong("author_id")).thenReturn(8L)

        assertThrows(PostNotOwnedException::class.java) { deletion().delete(42L, 7L) }

        assertEquals(1, calls.size)
        verify(jdbc, never()).update(any(PreparedStatementCreator::class.java))
    }

    @Test
    fun given__conditional_update_affects_no_row__when__delete__then__no_search_mutation_follows() {
        updateCount = 0

        assertThrows(PostNotFoundException::class.java) { deletion().delete(42L, 7L) }

        assertEquals(2, calls.size)
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 2, 3])
    fun given__lock_or_mutation_failure__when__delete__then__original_failure_leaves_transaction_without_retry(
        failingStatement: Int,
    ) {
        failAt = failingStatement

        assertSame(failure, assertThrows(IllegalStateException::class.java) { deletion().delete(42L, 7L) })

        assertFalse(transactionActive)
        assertEquals(failingStatement, calls.size)
        verify(transactions).execute(any<TransactionCallback<Void>>())
    }

    @Test
    fun given__commit_outcome_unknown__when__delete__then__original_transaction_failure_is_not_retried() {
        val unknown = TransactionSystemException("synthetic commit outcome unknown")
        doAnswer { call ->
            val callback = call.getArgument<TransactionCallback<Void>>(0)
            transactionActive = true
            try {
                callback.doInTransaction(SimpleTransactionStatus())
                // Model a transaction API reporting an unknown outcome after the SQL callback.
                throw unknown
            } finally {
                transactionActive = false
            }
        }.`when`(transactions).execute(any<TransactionCallback<Void>>())

        assertSame(unknown, assertThrows(TransactionSystemException::class.java) { deletion().delete(42L, 7L) })

        assertEquals(3, calls.size)
        assertFalse(transactionActive)
        verify(transactions).execute(any<TransactionCallback<Void>>())
    }

    private fun deletion(): JdbcPostDeletion = JdbcPostDeletion(jdbc, transactions, clock)

    private class StatementCall(val sql: String) {
        val values = LinkedHashMap<Int, Any?>()
        val calendars = LinkedHashMap<Int, Calendar>()
    }
}
