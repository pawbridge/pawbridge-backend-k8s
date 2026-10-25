package com.pawbridge.backend.community.internal.note.infrastructure

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when` as whenever
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant

class JdbcNoteExpiryTest {

    @ParameterizedTest
    @ValueSource(ints = [0, 17])
    fun given__zero_or_nonzero_expired_notes__when__expiring__then__sample_once_inside_transaction_delete_both_batches_and_return_only_notes(
        deletedNotes: Int,
    ) {
        val fixture = Fixture()
        fixture.jdbc.deletedNotes = deletedNotes

        assertEquals(deletedNotes, fixture.expiry.expire())

        assertEquals(listOf("begin", "clock", "notes", "requests", "commit", "cleanup"), fixture.events)
        assertEquals(listOf(NOTES_SQL, REQUESTS_SQL), fixture.jdbc.calls.map { it.sql })
        assertArrayEquals(arrayOf<Any?>(Timestamp.from(NOW)), fixture.jdbc.calls[0].arguments)
        assertArrayEquals(arrayOf<Any?>(Timestamp.from(NOW.minusSeconds(172800))), fixture.jdbc.calls[1].arguments)
        assertEquals(1, fixture.manager.commits)
        assertEquals(0, fixture.manager.rollbacks)
        fixture.assertReturned()
    }

    @ParameterizedTest
    @EnumSource(FailurePhase::class)
    fun given__clock_or_either_delete_fails__when__expiring__then__propagate_the_same_failure_without_later_sql_and_finish_rollback(
        phase: FailurePhase,
    ) {
        val fixture = Fixture()
        val failure: RuntimeException =
            if (phase == FailurePhase.CLOCK) {
                IllegalStateException("synthetic clock")
            } else {
                DataAccessResourceFailureException("synthetic SQL")
            }
        if (phase == FailurePhase.CLOCK) {
            fixture.clockFailure = failure
        } else {
            fixture.jdbc.failedStatement = if (phase == FailurePhase.NOTES) "notes" else "requests"
            fixture.jdbc.failure = failure
        }

        assertSame(failure, assertThrows(RuntimeException::class.java) { fixture.expiry.expire() })

        val expected = ArrayList(listOf("begin", "clock"))
        if (phase != FailurePhase.CLOCK) {
            expected.add("notes")
        }
        if (phase == FailurePhase.REQUESTS) {
            expected.add("requests")
        }
        expected.addAll(listOf("rollback", "cleanup"))
        assertEquals(expected, fixture.events)
        assertEquals(
            if (phase == FailurePhase.CLOCK) 0 else if (phase == FailurePhase.NOTES) 1 else 2,
            fixture.jdbc.calls.size,
        )
        assertEquals(0, fixture.manager.commits)
        assertEquals(1, fixture.manager.rollbacks)
        fixture.assertReturned()
    }

    enum class FailurePhase { CLOCK, NOTES, REQUESTS }

    private class Call(val sql: String, val arguments: Array<out Any?>)

    private class Fixture {
        val events = ArrayList<String>()
        val manager = Manager(events)
        val jdbc = RecordingJdbc(manager, events)
        val clock: Clock = mock(Clock::class.java)
        val expiry: JdbcNoteExpiry
        var clockFailure: RuntimeException? = null

        init {
            whenever(clock.instant()).thenAnswer {
                assertTrue(manager.active)
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
                events.add("clock")
                if (clockFailure != null) {
                    throw clockFailure!!
                }
                NOW
            }
            val transaction = TransactionTemplate(manager)
            transaction.timeout = 15
            expiry = JdbcNoteExpiry(jdbc, transaction, clock)
        }

        fun assertReturned() {
            verify(clock).instant()
            verifyNoMoreInteractions(clock)
            assertFalse(manager.active)
            assertFalse(TransactionSynchronizationManager.isSynchronizationActive())
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
        }
    }

    private class RecordingJdbc(
        private val manager: Manager,
        private val events: MutableList<String>,
    ) : JdbcTemplate() {
        val calls = ArrayList<Call>()
        var deletedNotes = 0
        var failedStatement: String? = null
        var failure: RuntimeException? = null

        override fun update(sql: String, vararg arguments: Any?): Int {
            assertTrue(manager.active)
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
            val normalized = sql.replace(Regex("\\s+"), " ").trim()
            val statement: String
            if (NOTES_SQL == normalized) {
                statement = "notes"
            } else {
                assertEquals(REQUESTS_SQL, normalized)
                statement = "requests"
            }
            calls.add(Call(normalized, arguments.clone()))
            events.add(statement)
            if (statement == failedStatement) {
                throw failure!!
            }
            return if (statement == "notes") deletedNotes else 11
        }
    }

    /** Real Spring synchronization with recording JDBC/TM; not database rollback, row-lock or FK evidence. */
    private class Manager(private val events: MutableList<String>) : AbstractPlatformTransactionManager() {
        var active = false
        var commits = 0
        var rollbacks = 0

        override fun doGetTransaction(): Any = Any()

        override fun doBegin(transaction: Any, definition: TransactionDefinition) {
            assertEquals(15, definition.timeout)
            assertFalse(definition.isReadOnly)
            events.add("begin")
            active = true
        }

        override fun doCommit(status: DefaultTransactionStatus) {
            events.add("commit")
            commits++
            active = false
        }

        override fun doRollback(status: DefaultTransactionStatus) {
            events.add("rollback")
            rollbacks++
            active = false
        }

        override fun doCleanupAfterCompletion(transaction: Any) {
            active = false
            events.add("cleanup")
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2031-01-01T00:00:00.123456789Z")
        const val NOTES_SQL =
            "WITH expired_notes AS MATERIALIZED ( SELECT note_id FROM pawbridge_community.private_notes " +
                "WHERE expires_at <= ? ORDER BY expires_at LIMIT 300 FOR UPDATE SKIP LOCKED ) " +
                "DELETE FROM pawbridge_community.private_notes WHERE note_id IN (SELECT note_id FROM expired_notes)"
        const val REQUESTS_SQL =
            "WITH expired_requests AS MATERIALIZED ( SELECT sender_id, request_id " +
                "FROM pawbridge_community.private_note_requests WHERE note_id IS NULL AND created_at < ? " +
                "ORDER BY created_at LIMIT 300 FOR UPDATE SKIP LOCKED ) " +
                "DELETE FROM pawbridge_community.private_note_requests " +
                "WHERE (sender_id, request_id) IN (SELECT sender_id, request_id FROM expired_requests)"
    }
}
