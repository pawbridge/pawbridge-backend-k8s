package com.pawbridge.backend.community.internal.note.infrastructure

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate

class JdbcNoteBlockReaderTest {
    @Test
    fun given__member_pair__when__reading__then__bind_pair_in_both_directions_inside_supplied_read_only_transaction() {
        val fixture = Fixture()

        assertTrue(fixture.reader.blocked(7L, 99L))

        assertArrayEquals(arrayOf<Any?>(7L, 99L, 99L, 7L), fixture.jdbc.arguments)
        assertEquals(1, fixture.jdbc.queries)
        assertEquals(1, fixture.transactions.commits)
        assertFalse(fixture.transactions.active)
    }

    @ParameterizedTest
    @EnumSource(FailurePhase::class)
    fun given__block_query_or_transaction_failure_then_recovery__when__reading__then__propagate_same_failure_without_user_wrapping_or_false_fallback(
        phase: FailurePhase,
    ) {
        val fixture = Fixture()
        val failure: RuntimeException = when (phase) {
            FailurePhase.BEGIN -> CannotCreateTransactionException("synthetic-private-begin")
            FailurePhase.QUERY_DAO -> DataAccessResourceFailureException("synthetic-private-block-sql")
            FailurePhase.QUERY_RUNTIME -> IllegalStateException("synthetic-private-block-runtime")
            FailurePhase.COMMIT -> TransactionSystemException("synthetic-private-commit")
        }
        when (phase) {
            FailurePhase.BEGIN -> fixture.transactions.startFailure = failure
            FailurePhase.QUERY_DAO, FailurePhase.QUERY_RUNTIME -> fixture.jdbc.failure = failure
            FailurePhase.COMMIT -> fixture.transactions.completionFailure = failure
        }

        assertSame(failure, assertThrows(RuntimeException::class.java) { fixture.reader.blocked(7L, 99L) })

        assertEquals(if (phase == FailurePhase.BEGIN) 0 else 1, fixture.jdbc.queries)
        assertFalse(fixture.transactions.active)
        fixture.transactions.startFailure = null
        fixture.transactions.completionFailure = null
        fixture.jdbc.failure = null

        assertTrue(fixture.reader.blocked(7L, 99L))
        assertEquals(if (phase == FailurePhase.BEGIN) 1 else 2, fixture.jdbc.queries)
        assertFalse(fixture.transactions.active)
    }

    enum class FailurePhase { BEGIN, QUERY_DAO, QUERY_RUNTIME, COMMIT }

    private class Fixture {
        val transactions = RecordingTransactionManager()
        val jdbc = RecordingJdbc(transactions)
        val reader: JdbcNoteBlockReader

        init {
            val transaction = TransactionTemplate(transactions)
            transaction.isReadOnly = true
            transaction.timeout = 3
            reader = JdbcNoteBlockReader(jdbc, transaction)
        }
    }

    private class RecordingJdbc(private val transactions: RecordingTransactionManager) : JdbcTemplate() {
        var arguments: Array<out Any?>? = null
        var queries = 0
        var failure: RuntimeException? = null

        override fun <T : Any?> queryForObject(sql: String, type: Class<T>, vararg arguments: Any?): T? {
            assertTrue(transactions.active)
            assertEquals(java.lang.Boolean::class.java, type)
            this.arguments = arguments
            queries++
            if (failure != null) {
                throw failure!!
            }
            return type.cast(true)
        }
    }

    private class RecordingTransactionManager : PlatformTransactionManager {
        var active = false
        var commits = 0
        var startFailure: RuntimeException? = null
        var completionFailure: RuntimeException? = null

        override fun getTransaction(definition: TransactionDefinition?): TransactionStatus {
            assertTrue(definition!!.isReadOnly)
            assertEquals(3, definition.timeout)
            if (startFailure != null) {
                throw startFailure!!
            }
            active = true
            return SimpleTransactionStatus()
        }

        override fun commit(status: TransactionStatus) {
            active = false
            commits++
            if (completionFailure != null) {
                throw completionFailure!!
            }
        }

        override fun rollback(status: TransactionStatus) {
            active = false
        }
    }
}
