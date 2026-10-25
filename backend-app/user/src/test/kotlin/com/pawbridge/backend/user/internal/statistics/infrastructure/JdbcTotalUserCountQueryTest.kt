package com.pawbridge.backend.user.internal.statistics.infrastructure

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

class JdbcTotalUserCountQueryTest {
    @Test
    fun given__physical_user_count_above_int_range__when__counting__then__query_only_id_column_once_and_return_raw_long_after_read_only_commit() {
        val fixture = Fixture()

        assertEquals(5_000_000_000L, fixture.query.count())

        assertEquals("SELECT count(user_id) FROM pawbridge_user.users", fixture.jdbc.sql)
        assertEquals(1, fixture.jdbc.queries)
        assertEquals(1, fixture.transactions.commits)
        assertEquals(0, fixture.transactions.rollbacks)
        assertFalse(fixture.transactions.active)
    }

    @Test
    fun given__unexpected_null_aggregate_then_recovery__when__counting__then__fail_instead_of_returning_zero_and_allow_the_next_read() {
        val fixture = Fixture()
        fixture.jdbc.count = null

        assertThrows(NullPointerException::class.java) { fixture.query.count() }

        assertEquals(1, fixture.jdbc.queries)
        assertFalse(fixture.transactions.active)
        fixture.jdbc.count = 7L
        assertEquals(7L, fixture.query.count())
        assertEquals(2, fixture.jdbc.queries)
        assertFalse(fixture.transactions.active)
    }

    @ParameterizedTest
    @EnumSource(FailurePhase::class)
    fun given__count_query_or_transaction_failure_then_recovery__when__counting__then__propagate_same_failure_without_zero_or_typed_contact_fallback(
        phase: FailurePhase,
    ) {
        val fixture = Fixture()
        val failure: RuntimeException = when (phase) {
            FailurePhase.BEGIN -> CannotCreateTransactionException("synthetic-private-begin")
            FailurePhase.QUERY_DAO -> DataAccessResourceFailureException("synthetic-private-user-count-sql")
            FailurePhase.QUERY_RUNTIME -> IllegalStateException("synthetic-private-count-runtime")
            FailurePhase.COMMIT -> TransactionSystemException("synthetic-private-commit")
        }
        when (phase) {
            FailurePhase.BEGIN -> fixture.transactions.startFailure = failure
            FailurePhase.QUERY_DAO, FailurePhase.QUERY_RUNTIME -> fixture.jdbc.failure = failure
            FailurePhase.COMMIT -> fixture.transactions.completionFailure = failure
        }

        assertSame(failure, assertThrows(RuntimeException::class.java) { fixture.query.count() })

        assertEquals(if (phase == FailurePhase.BEGIN) 0 else 1, fixture.jdbc.queries)
        assertEquals(
            if (phase == FailurePhase.QUERY_DAO || phase == FailurePhase.QUERY_RUNTIME) 1 else 0,
            fixture.transactions.rollbacks,
        )
        assertFalse(fixture.transactions.active)
        fixture.transactions.startFailure = null
        fixture.transactions.completionFailure = null
        fixture.jdbc.failure = null
        assertEquals(5_000_000_000L, fixture.query.count())
        assertEquals(if (phase == FailurePhase.BEGIN) 1 else 2, fixture.jdbc.queries)
        assertFalse(fixture.transactions.active)
    }

    enum class FailurePhase { BEGIN, QUERY_DAO, QUERY_RUNTIME, COMMIT }

    private class Fixture {
        val transactions = RecordingTransactionManager()
        val jdbc = RecordingJdbc(transactions)
        val query: JdbcTotalUserCountQuery

        init {
            val transaction = TransactionTemplate(transactions)
            transaction.isReadOnly = true
            transaction.timeout = 3
            query = JdbcTotalUserCountQuery(jdbc, transaction)
        }
    }

    private class RecordingJdbc(private val transactions: RecordingTransactionManager) : JdbcTemplate() {
        var count: Long? = 5_000_000_000L
        var sql: String? = null
        var queries = 0
        var failure: RuntimeException? = null

        override fun <T> queryForObject(sql: String, requiredType: Class<T>): T? {
            assertTrue(transactions.active)
            assertEquals(Long::class.javaObjectType, requiredType)
            this.sql = sql
            queries++
            if (failure != null) {
                throw failure!!
            }
            return requiredType.cast(count)
        }
    }

    private class RecordingTransactionManager : PlatformTransactionManager {
        var active = false
        var commits = 0
        var rollbacks = 0
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
            rollbacks++
        }
    }
}
