package com.pawbridge.backend.community.internal.visit.infrastructure

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Date
import java.time.LocalDate

class JdbcVisitCountReaderTest {

    @ParameterizedTest
    @ValueSource(longs = [0L, 3_000_000_001L, Long.MAX_VALUE])
    fun given__stored_count__when__reading__then__bind_exact_sql_date_and_preserve_long_after_read_only_completion(
        count: Long,
    ) {
        val fixture = Fixture()
        fixture.jdbc.rows = listOf(count)

        assertEquals(count, fixture.reader.count(DAY))

        assertEquals(
            "SELECT unique_visitors FROM pawbridge_community.daily_visit_count WHERE visit_date = ?",
            fixture.jdbc.sql!!.replace(Regex("\\s+"), " ").trim(),
        )
        assertArrayEquals(arrayOf<Any?>(Date.valueOf(DAY)), fixture.jdbc.arguments)
        assertEquals(1, fixture.jdbc.queries)
        assertEquals(1, fixture.transactions.commits)
        assertEquals(0, fixture.transactions.rollbacks)
        assertFalse(fixture.transactions.active)
    }

    @Test
    fun given__no_daily_row__when__reading__then__return_zero_after_read_only_completion() {
        val fixture = Fixture()

        assertEquals(0L, fixture.reader.count(DAY))

        assertEquals(1, fixture.jdbc.queries)
        assertEquals(1, fixture.transactions.commits)
        assertEquals(0, fixture.transactions.rollbacks)
        assertFalse(fixture.transactions.active)
    }

    @Test
    fun given__query_failure_then_recovery__when__reading__then__propagate_without_zero_fallback_and_release_transaction() {
        val fixture = Fixture()
        val failure = DataAccessResourceFailureException("synthetic-private-query")
        fixture.jdbc.failure = failure

        assertSame(
            failure,
            assertThrows(DataAccessResourceFailureException::class.java) { fixture.reader.count(DAY) },
        )
        assertEquals(1, fixture.transactions.rollbacks)
        assertEquals(0, fixture.transactions.commits)
        assertFalse(fixture.transactions.active)
        fixture.jdbc.failure = null
        fixture.jdbc.rows = listOf(77L)

        assertEquals(77L, fixture.reader.count(DAY))
        assertEquals(2, fixture.jdbc.queries)
        assertEquals(1, fixture.transactions.commits)
        assertFalse(fixture.transactions.active)
    }

    @Test
    fun given__transaction_start_failure_then_recovery__when__reading__then__no_query_precedes_successful_begin() {
        val fixture = Fixture()
        val failure = CannotCreateTransactionException("synthetic-private-begin")
        fixture.transactions.startFailure = failure

        assertSame(
            failure,
            assertThrows(CannotCreateTransactionException::class.java) { fixture.reader.count(DAY) },
        )
        assertEquals(0, fixture.jdbc.queries)
        assertEquals(0, fixture.transactions.commits)
        assertEquals(0, fixture.transactions.rollbacks)
        assertFalse(fixture.transactions.active)
        fixture.transactions.startFailure = null

        assertEquals(0L, fixture.reader.count(DAY))
        assertEquals(1, fixture.jdbc.queries)
        assertEquals(1, fixture.transactions.commits)
        assertFalse(fixture.transactions.active)
    }

    @Test
    fun given__transaction_completion_failure_then_recovery__when__reading__then__no_count_is_returned_before_successful_completion() {
        val fixture = Fixture()
        val failure = TransactionSystemException("synthetic-private-completion")
        fixture.transactions.completionFailure = failure

        assertSame(
            failure,
            assertThrows(TransactionSystemException::class.java) { fixture.reader.count(DAY) },
        )
        assertEquals(1, fixture.jdbc.queries)
        assertEquals(1, fixture.transactions.commits)
        assertFalse(fixture.transactions.active)
        fixture.transactions.completionFailure = null
        fixture.jdbc.rows = listOf(77L)

        assertEquals(77L, fixture.reader.count(DAY))
        assertEquals(2, fixture.jdbc.queries)
        assertEquals(2, fixture.transactions.commits)
        assertFalse(fixture.transactions.active)
    }

    private class Fixture {
        val transactions = RecordingTransactionManager()
        val jdbc = RecordingJdbc(transactions)
        val reader: JdbcVisitCountReader

        init {
            val tx = TransactionTemplate(transactions)
            tx.isReadOnly = true
            tx.timeout = 3
            reader = JdbcVisitCountReader(jdbc, tx)
        }
    }

    private class RecordingJdbc(private val transactions: RecordingTransactionManager) : JdbcTemplate() {
        var rows: List<Long> = listOf()
        var sql: String? = null
        var arguments: Array<out Any?>? = null
        var queries = 0
        var failure: RuntimeException? = null

        override fun <T : Any?> queryForList(sql: String, type: Class<T>, vararg arguments: Any?): List<T> {
            assertTrue(
                transactions.active,
                "The daily count must be read inside the supplied read-only transaction.",
            )
            assertEquals(java.lang.Long::class.java, type)
            this.sql = sql
            this.arguments = arguments
            queries++
            if (failure != null) {
                throw failure!!
            }
            return rows.map { type.cast(it) }
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

    private companion object {
        val DAY: LocalDate = LocalDate.of(2026, 10, 20)
    }
}
