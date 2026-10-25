package com.pawbridge.backend.user.internal.favorite.infrastructure

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

class JdbcFavoriteStatusQueryTest {
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun given__matching_or_absent_favorite__when__checking__then__two_id_exists_query_returns_boolean_after_read_only_commit(
        exists: Boolean,
    ) {
        val fixture = Fixture()
        fixture.jdbc.exists = exists

        assertEquals(exists, fixture.query.isFavorite(52L, 73L))

        assertEquals(
            "SELECT EXISTS ( SELECT 1 FROM pawbridge_user.favorites WHERE user_id = ? AND animal_id = ? )",
            fixture.jdbc.sql!!.replace(Regex("\\s+"), " ").trim(),
        )
        assertArrayEquals(arrayOf<Any?>(52L, 73L), fixture.jdbc.arguments)
        assertEquals(1, fixture.jdbc.queries)
        assertEquals(1, fixture.transactions.commits)
        assertEquals(0, fixture.transactions.rollbacks)
        assertFalse(fixture.transactions.active)
    }

    @ParameterizedTest
    @ValueSource(longs = [0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE])
    fun given__zero_negative_or_boundary_animal_id__when__checking__then__exact_long_is_queried_without_positive_validation(
        animalId: Long,
    ) {
        val fixture = Fixture()

        assertFalse(fixture.query.isFavorite(52L, animalId))

        assertArrayEquals(arrayOf<Any?>(52L, animalId), fixture.jdbc.arguments)
        assertEquals(1, fixture.jdbc.queries)
        assertEquals(1, fixture.transactions.commits)
        assertFalse(fixture.transactions.active)
    }

    @Test
    fun given__jdbc_failure_then_recovery__when__checking__then__failure_rolls_back_without_false_fallback_and_next_query_commits() {
        val fixture = Fixture()
        val failure = DataAccessResourceFailureException("synthetic-private-sql")
        fixture.jdbc.failure = failure

        assertSame(failure, assertThrows(DataAccessResourceFailureException::class.java) { fixture.query.isFavorite(52L, 73L) })

        assertEquals(1, fixture.jdbc.queries)
        assertEquals(0, fixture.transactions.commits)
        assertEquals(1, fixture.transactions.rollbacks)
        assertFalse(fixture.transactions.active)
        fixture.jdbc.failure = null
        fixture.jdbc.exists = true
        assertTrue(fixture.query.isFavorite(52L, 73L))
        assertEquals(2, fixture.jdbc.queries)
        assertEquals(1, fixture.transactions.commits)
        assertFalse(fixture.transactions.active)
    }

    @Test
    fun given__transaction_start_failure_then_recovery__when__checking__then__no_query_precedes_failure_and_next_query_commits() {
        val fixture = Fixture()
        val failure = CannotCreateTransactionException("synthetic-private-start")
        fixture.transactions.startFailure = failure

        assertSame(failure, assertThrows(CannotCreateTransactionException::class.java) { fixture.query.isFavorite(52L, 73L) })

        assertEquals(0, fixture.jdbc.queries)
        assertEquals(0, fixture.transactions.commits)
        assertEquals(0, fixture.transactions.rollbacks)
        assertFalse(fixture.transactions.active)
        fixture.transactions.startFailure = null
        assertFalse(fixture.query.isFavorite(52L, 73L))
        assertEquals(1, fixture.jdbc.queries)
        assertEquals(1, fixture.transactions.commits)
        assertFalse(fixture.transactions.active)
    }

    @Test
    fun given__transaction_completion_failure_then_recovery__when__checking__then__no_boolean_is_returned_before_successful_completion() {
        val fixture = Fixture()
        val failure = TransactionSystemException("synthetic-private-completion")
        fixture.transactions.completionFailure = failure

        assertSame(failure, assertThrows(TransactionSystemException::class.java) { fixture.query.isFavorite(52L, 73L) })

        assertEquals(1, fixture.jdbc.queries)
        assertEquals(1, fixture.transactions.commits)
        assertFalse(fixture.transactions.active)
        fixture.transactions.completionFailure = null
        fixture.jdbc.exists = true
        assertTrue(fixture.query.isFavorite(52L, 73L))
        assertEquals(2, fixture.jdbc.queries)
        assertEquals(2, fixture.transactions.commits)
        assertFalse(fixture.transactions.active)
    }

    private class Fixture {
        val transactions = RecordingTransactionManager()
        val jdbc = RecordingJdbc(transactions)
        val query: JdbcFavoriteStatusQuery

        init {
            val readOnlyTransaction = TransactionTemplate(transactions)
            readOnlyTransaction.isReadOnly = true
            readOnlyTransaction.timeout = 3
            query = JdbcFavoriteStatusQuery(jdbc, readOnlyTransaction)
        }
    }

    private class RecordingJdbc(private val transactions: RecordingTransactionManager) : JdbcTemplate() {
        var sql: String? = null
        var arguments: Array<out Any?>? = null
        var queries = 0
        var exists = false
        var failure: RuntimeException? = null

        override fun <T> queryForObject(sql: String, requiredType: Class<T>, vararg args: Any?): T? {
            assertTrue(transactions.active, "The existence query must run inside the existing read-only transaction.")
            this.sql = sql
            this.arguments = args
            queries++
            if (failure != null) {
                throw failure!!
            }
            return requiredType.cast(exists)
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
