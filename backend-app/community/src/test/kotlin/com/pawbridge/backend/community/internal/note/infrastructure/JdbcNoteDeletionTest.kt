package com.pawbridge.backend.community.internal.note.infrastructure

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.pawbridge.backend.community.internal.note.application.NoteChangeHints
import com.pawbridge.backend.community.internal.note.application.NoteDeletion
import com.pawbridge.backend.community.support.uninitialized
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.ArrayDeque
import java.util.UUID
import java.util.function.Supplier

class JdbcNoteDeletionTest {

    @ParameterizedTest
    @CsvSource("0, 0", "1, 1")
    fun given__zero_or_one_deleted_rows__when__deleting__then__bind_two_distinct_times_delete_mailbox_before_orphan_and_hint_only_after_commit(
        mailboxRows: Int,
        noteRows: Int,
    ) {
        val fixture = Fixture()
        fixture.jdbc.mailboxRows = mailboxRows
        fixture.jdbc.noteRows = noteRows

        fixture.delete()

        assertEquals(
            listOf(
                "begin", "insert", "member-lock", "guard", "clock:1", "find:1", "note-lock",
                "clock:2", "find:2", "delete-mailbox", "delete-orphan", "commit", "hint:7", "cleanup",
            ),
            fixture.events,
        )
        assertEquals(
            listOf(INSERT, MEMBER_LOCK, FIND, NOTE_LOCK, FIND, DELETE_MAILBOX, DELETE_ORPHAN),
            fixture.jdbc.calls.map { it.sql },
        )
        assertArrayEquals(arrayOf<Any?>(7L), fixture.jdbc.calls[0].arguments)
        assertArrayEquals(arrayOf<Any?>(7L), fixture.jdbc.calls[1].arguments)
        assertArrayEquals(arrayOf<Any?>(7L, NOTE, Timestamp.from(FIRST)), fixture.jdbc.calls[2].arguments)
        assertArrayEquals(arrayOf<Any?>(NOTE), fixture.jdbc.calls[3].arguments)
        assertArrayEquals(arrayOf<Any?>(7L, NOTE, Timestamp.from(FIRST.plusSeconds(1))), fixture.jdbc.calls[4].arguments)
        assertArrayEquals(arrayOf<Any?>(7L, NOTE), fixture.jdbc.calls[5].arguments)
        assertArrayEquals(arrayOf<Any?>(NOTE), fixture.jdbc.calls[6].arguments)
        assertEquals(1, fixture.guards)
        assertEquals(2, fixture.samples)
        assertEquals(listOf(7L), fixture.hinted)
        assertEquals(1, fixture.manager.commits)
        assertEquals(0, fixture.manager.rollbacks)
        fixture.assertReturned()
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 2])
    fun given__ownership_absence_before_or_after_a_zero_row_note_lock__when__deleting__then__roll_back_without_either_delete_or_hint(
        missingAt: Int,
    ) {
        val fixture = Fixture()
        fixture.jdbc.found.clear()
        if (missingAt == 2) {
            fixture.jdbc.found.add(true)
        }
        fixture.jdbc.found.add(false)
        fixture.jdbc.noteLockRows = 0

        assertThrows(NoteDeletion.MissingOwnedNote::class.java) { fixture.delete() }

        val expected = ArrayList(listOf("begin", "insert", "member-lock", "guard", "clock:1", "find:1"))
        if (missingAt == 2) {
            expected.addAll(listOf("note-lock", "clock:2", "find:2"))
        }
        expected.addAll(listOf("rollback", "cleanup"))
        assertEquals(expected, fixture.events)
        assertEquals(missingAt, fixture.samples)
        assertEquals(if (missingAt == 1) 3 else 5, fixture.jdbc.calls.size)
        assertEquals(1, fixture.guards)
        assertEquals(1, fixture.manager.rollbacks)
        assertEquals(0, fixture.manager.commits)
        assertTrue(fixture.hinted.isEmpty())
        fixture.assertReturned()
    }

    @ParameterizedTest
    @EnumSource(FailurePhase::class)
    fun given__begin_member_guard_lock_either_delete_or_commit_failure__when__deleting__then__propagate_without_retry_or_any_hint(
        phase: FailurePhase,
    ) {
        val fixture = Fixture()
        val failure: RuntimeException = when (phase) {
            FailurePhase.BEGIN -> CannotCreateTransactionException("synthetic begin")
            FailurePhase.MEMBER_INSERT -> DataIntegrityViolationException("synthetic member CHECK")
            FailurePhase.NOTE_LOCK, FailurePhase.MAILBOX_DELETE, FailurePhase.NOTE_DELETE ->
                DataAccessResourceFailureException("synthetic SQL")
            FailurePhase.GUARD -> IllegalStateException("synthetic guard")
            FailurePhase.COMMIT -> TransactionSystemException("synthetic commit")
        }
        when (phase) {
            FailurePhase.BEGIN -> fixture.manager.beginFailure = failure
            FailurePhase.COMMIT -> fixture.manager.commitFailure = failure
            FailurePhase.GUARD -> fixture.guardFailure = failure
            else -> {
                fixture.jdbc.failEvent =
                    if (phase == FailurePhase.MEMBER_INSERT) {
                        "insert"
                    } else if (phase == FailurePhase.NOTE_LOCK) {
                        "note-lock"
                    } else if (phase == FailurePhase.MAILBOX_DELETE) {
                        "delete-mailbox"
                    } else {
                        "delete-orphan"
                    }
                fixture.jdbc.failure = failure
            }
        }

        assertSame(failure, assertThrows(RuntimeException::class.java) { fixture.delete() })

        val stages = listOf(
            "begin", "insert", "member-lock", "guard", "clock:1", "find:1", "note-lock",
            "clock:2", "find:2", "delete-mailbox", "delete-orphan", "commit",
        )
        val last = when (phase) {
            FailurePhase.BEGIN -> 0
            FailurePhase.MEMBER_INSERT -> 1
            FailurePhase.GUARD -> 3
            FailurePhase.NOTE_LOCK -> 6
            FailurePhase.MAILBOX_DELETE -> 9
            FailurePhase.NOTE_DELETE -> 10
            FailurePhase.COMMIT -> 11
        }
        val expected = ArrayList(stages.subList(0, last + 1))
        if (phase != FailurePhase.BEGIN && phase != FailurePhase.COMMIT) {
            expected.add("rollback")
        }
        if (phase != FailurePhase.BEGIN) {
            expected.add("cleanup")
        }
        assertEquals(expected, fixture.events)
        assertTrue(fixture.hinted.isEmpty())
        assertEquals(if (phase == FailurePhase.COMMIT) 1 else 0, fixture.manager.commits)
        assertEquals(if (phase == FailurePhase.BEGIN || phase == FailurePhase.COMMIT) 0 else 1, fixture.manager.rollbacks)
        fixture.assertReturned()
    }

    @Test
    fun given__private_hint_runtime_failure_after_commit__when__notifying__then__keep_success_and_log_only_type_without_private_messages() {
        val fixture = Fixture()
        fixture.hintFailure = IllegalStateException("Bearer synthetic-private-token", SQLException("synthetic-private-SQL"))
        val logger = LoggerFactory.getLogger(JdbcNoteDeletion::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        try {
            assertDoesNotThrow(Executable { fixture.delete() })

            assertEquals(
                listOf("commit", "hint:7", "cleanup"),
                fixture.events.subList(fixture.events.size - 3, fixture.events.size),
            )
            assertEquals(1, fixture.manager.commits)
            assertEquals(0, fixture.manager.rollbacks)
            assertEquals(listOf(7L), fixture.hinted)
            assertEquals(1, appender.list.size)
            val event = appender.list.first()
            assertEquals(
                "Private note push failed (IllegalStateException); REST restoration remains available",
                event.formattedMessage,
            )
            assertNull(event.throwableProxy)
            assertArrayEquals(arrayOf<Any?>("IllegalStateException"), event.argumentArray)
            fixture.assertReturned()
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    enum class FailurePhase { BEGIN, MEMBER_INSERT, GUARD, NOTE_LOCK, MAILBOX_DELETE, NOTE_DELETE, COMMIT }

    private class Call(val sql: String, val arguments: Array<out Any?>)

    private class Fixture {
        val events = ArrayList<String>()
        val hinted = ArrayList<Long>()
        val manager = SynchronizingManager(events)
        val jdbc = RecordingJdbc(manager, events)
        val mutation: JdbcNoteDeletion
        var guards = 0
        var samples = 0
        var guardFailure: RuntimeException? = null
        var hintFailure: RuntimeException? = null

        init {
            val transaction = TransactionTemplate(manager)
            transaction.timeout = 15
            mutation = JdbcNoteDeletion(
                jdbc,
                transaction,
                NoteChangeHints { current ->
                    // The commit marker proves ordering; Spring resources/actualTxActive may still be bound here.
                    assertEquals(1, manager.commits)
                    assertEquals("commit", events.last())
                    events.add("hint:$current")
                    hinted.add(current)
                    if (hintFailure != null) {
                        throw hintFailure!!
                    }
                },
            )
        }

        fun delete() {
            val now = Supplier<Instant> {
                samples++
                events.add("clock:$samples")
                FIRST.plusSeconds((samples - 1).toLong())
            }
            mutation.delete(
                7L,
                NOTE,
                Runnable {
                    assertTrue(manager.active)
                    events.add("guard")
                    guards++
                    if (guardFailure != null) {
                        throw guardFailure!!
                    }
                },
                now,
            )
        }

        fun assertReturned() {
            assertFalse(manager.active)
            assertFalse(TransactionSynchronizationManager.isSynchronizationActive())
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
        }
    }

    private class RecordingJdbc(
        private val manager: SynchronizingManager,
        private val events: MutableList<String>,
    ) : JdbcTemplate() {
        val calls = ArrayList<Call>()
        val found = ArrayDeque<Boolean>(listOf(true, true))
        var finds = 0
        var noteLockRows = 1
        var mailboxRows = 1
        var noteRows = 1
        var failEvent: String? = null
        var failure: RuntimeException? = null

        override fun update(sql: String, vararg arguments: Any?): Int {
            val event = if (normalize(sql).startsWith("INSERT")) {
                "insert"
            } else if (normalize(sql).startsWith("DELETE FROM pawbridge_community.private_note_mailboxes")) {
                "delete-mailbox"
            } else {
                "delete-orphan"
            }
            record(sql, arguments, event)
            return if (event == "insert") 0 else if (event == "delete-mailbox") mailboxRows else noteRows
        }

        override fun <T : Any?> queryForObject(sql: String, type: Class<T>, vararg arguments: Any?): T? {
            assertEquals(java.lang.Long::class.java, type)
            record(sql, arguments, "member-lock")
            return type.cast(arguments[0])
        }

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any?> query(sql: String, mapper: RowMapper<T>, vararg arguments: Any?): List<T> {
            val existence = normalize(sql).startsWith("SELECT 1 ")
            val event: String
            if (existence) {
                finds++
                event = "find:$finds"
            } else {
                event = "note-lock"
            }
            record(sql, arguments, event)
            if (if (existence) !found.removeFirst() else noteLockRows == 0) {
                return listOf()
            }
            try {
                return listOf(mapper.mapRow(uninitialized<ResultSet>(), 0) as T)
            } catch (failure: SQLException) {
                throw AssertionError("Recording row mapper failed", failure)
            }
        }

        private fun record(sql: String, arguments: Array<out Any?>, event: String) {
            assertTrue(manager.active)
            calls.add(Call(normalize(sql), arguments.clone()))
            events.add(event)
            if (event == failEvent) {
                throw failure!!
            }
        }

        private fun normalize(sql: String): String = sql.replace(Regex("\\s+"), " ").trim()
    }

    /** Real Spring synchronization and lifecycle; recording commit/rollback are not database transaction evidence. */
    private class SynchronizingManager(private val events: MutableList<String>) : AbstractPlatformTransactionManager() {
        var active = false
        var commits = 0
        var rollbacks = 0
        var beginFailure: RuntimeException? = null
        var commitFailure: RuntimeException? = null

        override fun doGetTransaction(): Any = Any()

        override fun doBegin(transaction: Any, definition: TransactionDefinition) {
            assertEquals(15, definition.timeout)
            assertFalse(definition.isReadOnly)
            assertFalse(active)
            events.add("begin")
            if (beginFailure != null) {
                throw beginFailure!!
            }
            active = true
        }

        override fun doCommit(status: DefaultTransactionStatus) {
            events.add("commit")
            commits++
            active = false
            if (commitFailure != null) {
                throw commitFailure!!
            }
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
        val NOTE: UUID = UUID.fromString("f2800000-0000-0000-0000-000000000001")
        val FIRST: Instant = Instant.parse("2031-01-01T00:00:00.123456Z")
        const val INSERT =
            "INSERT INTO pawbridge_community.private_note_members(member_id) VALUES (?) ON CONFLICT DO NOTHING"
        const val MEMBER_LOCK =
            "SELECT member_id FROM pawbridge_community.private_note_members WHERE member_id = ? FOR UPDATE"
        const val FIND =
            "SELECT 1 FROM pawbridge_community.private_note_mailboxes m JOIN pawbridge_community.private_notes n ON n.note_id = m.note_id WHERE m.member_id = ? AND n.note_id = ? AND n.expires_at > ?"
        const val NOTE_LOCK = "SELECT note_id FROM pawbridge_community.private_notes WHERE note_id = ? FOR UPDATE"
        const val DELETE_MAILBOX =
            "DELETE FROM pawbridge_community.private_note_mailboxes WHERE member_id = ? AND note_id = ?"
        const val DELETE_ORPHAN =
            "DELETE FROM pawbridge_community.private_notes n WHERE note_id = ? AND NOT EXISTS( SELECT 1 FROM pawbridge_community.private_note_mailboxes m WHERE m.note_id = n.note_id )"
    }
}
