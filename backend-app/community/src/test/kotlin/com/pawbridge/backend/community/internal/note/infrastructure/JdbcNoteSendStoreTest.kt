package com.pawbridge.backend.community.internal.note.infrastructure

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.pawbridge.backend.community.internal.note.application.NoteDeliveryHints
import com.pawbridge.backend.community.internal.note.application.NoteNotifications.Notification
import com.pawbridge.backend.community.internal.note.application.NoteSendRejectedException
import com.pawbridge.backend.community.internal.note.application.NoteSendStore
import com.pawbridge.backend.community.internal.note.application.SendPrivateNote.Receipt
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when` as whenever
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessResourceFailureException
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
import java.util.Optional
import java.util.UUID
import java.util.function.Supplier

class JdbcNoteSendStoreTest {

    @ParameterizedTest
    @CsvSource("9,3", "3,3")
    fun given__reversed_or_same_member_pair__when__locking_in_the_callback__then__insert_and_lock_each_distinct_id_in_ascending_order(
        first: Long,
        second: Long,
    ) {
        val f = Fixture()
        assertEquals(
            Receipt(NOTE),
            f.store.inTransaction(
                Supplier {
                    f.store.lockMembers(first, second)
                    Receipt(NOTE)
                },
            ),
        )
        val expected = if (first == second) listOf(3L) else listOf(3L, 9L)
        assertEquals(expected.size * 2, f.jdbc.calls.size)
        for (index in expected.indices) {
            assertEquals(MEMBER_INSERT, f.jdbc.calls[index * 2].sql)
            assertEquals(MEMBER_LOCK, f.jdbc.calls[index * 2 + 1].sql)
            assertArrayEquals(arrayOf<Any?>(expected[index]), f.jdbc.calls[index * 2].arguments)
            assertArrayEquals(arrayOf<Any?>(expected[index]), f.jdbc.calls[index * 2 + 1].arguments)
        }
        assertEquals(1, f.manager.commits)
        assertEquals(0, f.manager.rollbacks)
        assertTrue(f.published.isEmpty())
        f.returned()
    }

    @ParameterizedTest
    @ValueSource(strings = ["present", "null", "absent"])
    fun given__normal_nullable_or_absent_rows__when__reading_send_facts__then__keep_raw_projection_boundaries_direction_and_inclusive_parameterized_sql(
        rows: String,
    ) {
        val f = Fixture()
        f.jdbc.rowKind = rows
        f.store.inTransaction(
            Supplier {
                assertEquals(
                    if (rows == "absent") {
                        Optional.empty()
                    } else {
                        Optional.of(
                            NoteSendStore.PreviousRequest(
                                if (rows == "null") null else NOTE,
                                if (rows == "null") null else "stored-hash",
                            ),
                        )
                    },
                    f.store.request(7L, REQUEST),
                )
                assertTrue(f.store.blocked(7L, 8L))
                assertEquals(9L, f.store.rate(7L, NOW.minusSeconds(60)))
                assertEquals(
                    if (rows == "absent") {
                        Optional.empty()
                    } else {
                        Optional.of(NoteSendStore.Reply("INBOX", if (rows == "null") null else 8L))
                    },
                    f.store.reply(7L, PARENT, NOW),
                )
                assertTrue(f.store.contextAuthor("POST", 3L, 8L))
                assertTrue(f.store.contextAuthor("REPORT", 4L, 8L))
                Receipt(NOTE)
            },
        )
        assertEquals(6, f.jdbc.calls.size)
        assertCall(
            f, 0,
            "SELECT note_id, request_hash FROM pawbridge_community.private_note_requests WHERE sender_id = ? AND request_id = ?",
            7L, REQUEST,
        )
        assertCall(
            f, 1,
            "SELECT EXISTS( SELECT 1 FROM pawbridge_community.private_note_blocks WHERE (member_id = ? AND blocked_id = ?) OR (member_id = ? AND blocked_id = ?) )",
            7L, 8L, 8L, 7L,
        )
        assertCall(
            f, 2,
            "SELECT count(*) FROM pawbridge_community.private_note_requests WHERE sender_id = ? AND created_at >= ?",
            7L, Timestamp.from(NOW.minusSeconds(60)),
        )
        assertCall(
            f, 3,
            "SELECT m.direction, n.sender_id FROM pawbridge_community.private_note_mailboxes m JOIN pawbridge_community.private_notes n ON n.note_id = m.note_id WHERE m.member_id = ? AND n.note_id = ? AND n.expires_at > ?",
            7L, PARENT, Timestamp.from(NOW),
        )
        assertCall(
            f, 4,
            "SELECT EXISTS( SELECT 1 FROM pawbridge_community.posts WHERE post_id = ? AND author_id = ? AND deleted_at IS NULL )",
            3L, 8L,
        )
        assertCall(
            f, 5,
            "SELECT EXISTS( SELECT 1 FROM pawbridge_community.animal_reports WHERE report_id = ? AND author_id = ? AND deleted_at IS NULL )",
            4L, 8L,
        )
        assertTrue(f.published.isEmpty())
        f.returned()
    }

    @Test
    fun given__new_note_and_notification__when__the_callback_inserts__then__bind_three_statements_and_publish_only_recipient_after_commit() {
        val f = Fixture()
        assertEquals(Receipt(NOTE), f.insert())
        assertEquals(listOf("begin", "callback", "note", "mailboxes", "request", "commit", "publish:8", "cleanup"), f.events)
        assertEquals(3, f.jdbc.calls.size)
        assertCall(
            f, 0, NOTE_INSERT,
            NOTE, 7L, 8L, "raw body\n", PARENT, "REPORT", 17L, Timestamp.from(NOW), Timestamp.from(EXPIRY),
        )
        assertCall(
            f, 1, MAILBOX_INSERT,
            7L, NOTE, Timestamp.from(NOW), Timestamp.from(NOW), 8L, NOTE, Timestamp.from(NOW),
        )
        assertCall(f, 2, REQUEST_INSERT, 7L, REQUEST, "private-hash", NOTE, Timestamp.from(NOW))
        assertEquals(listOf(8L), f.published)
        assertSame(HINT, f.notification)
        assertEquals(1, f.manager.commits)
        assertEquals(0, f.manager.rollbacks)
        f.returned()
    }

    @ParameterizedTest
    @EnumSource(Failure::class)
    fun given__begin_callback_any_insert_or_commit_failure__when__running_send__then__propagate_without_retry_or_publication_and_release_synchronization(
        phase: Failure,
    ) {
        val f = Fixture()
        val failure: RuntimeException = when (phase) {
            Failure.BEGIN -> CannotCreateTransactionException("private begin")
            Failure.CALLBACK -> NoteSendRejectedException(NoteSendRejectedException.Reason.BLOCKED)
            Failure.COMMIT -> TransactionSystemException("private commit")
            else -> DataAccessResourceFailureException("private SQL")
        }
        when (phase) {
            Failure.BEGIN -> f.manager.beginFailure = failure
            Failure.COMMIT -> f.manager.commitFailure = failure
            Failure.CALLBACK -> f.callbackFailure = failure
            else -> {
                f.jdbc.failEvent = when (phase) {
                    Failure.NOTE -> "note"
                    Failure.MAILBOXES -> "mailboxes"
                    Failure.REQUEST -> "request"
                    else -> throw AssertionError()
                }
                f.jdbc.failure = failure
            }
        }

        assertSame(failure, assertThrows(RuntimeException::class.java) { f.insert() })

        val stages = listOf("begin", "callback", "note", "mailboxes", "request", "commit")
        val last = when (phase) {
            Failure.BEGIN -> 0
            Failure.CALLBACK -> 1
            Failure.NOTE -> 2
            Failure.MAILBOXES -> 3
            Failure.REQUEST -> 4
            Failure.COMMIT -> 5
        }
        val expected = ArrayList(stages.subList(0, last + 1))
        if (phase != Failure.BEGIN && phase != Failure.COMMIT) {
            expected.add("rollback")
        }
        if (phase != Failure.BEGIN) {
            expected.add("cleanup")
        }
        assertEquals(expected, f.events)
        assertTrue(f.published.isEmpty())
        assertEquals(if (phase == Failure.COMMIT) 1 else 0, f.manager.commits)
        assertEquals(if (phase == Failure.BEGIN || phase == Failure.COMMIT) 0 else 1, f.manager.rollbacks)
        f.returned()
    }

    @Test
    fun given__private_publication_runtime_after_commit__when__sending_the_hint__then__keep_saved_success_and_log_only_the_type_without_throwable() {
        val f = Fixture()
        f.hintFailure = IllegalStateException("Bearer private-token body hash", SQLException("private SQL"))
        val logger = LoggerFactory.getLogger(JdbcNoteSendStore::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        try {
            assertEquals(Receipt(NOTE), f.insert())
            assertEquals(listOf(8L), f.published)
            assertEquals(1, f.manager.commits)
            assertEquals(0, f.manager.rollbacks)
            assertEquals(1, appender.list.size)
            val event = appender.list.first()
            assertEquals(
                "Private note push failed (IllegalStateException); REST restoration remains available",
                event.formattedMessage,
            )
            assertNull(event.throwableProxy)
            assertArrayEquals(arrayOf<Any?>("IllegalStateException"), event.argumentArray)
            f.returned()
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    enum class Failure { BEGIN, CALLBACK, NOTE, MAILBOXES, REQUEST, COMMIT }

    private class Call(val sql: String, val arguments: Array<out Any?>)

    /** Real Spring synchronization, recording JDBC/commit: not actual rollback, locks, FK or SSE evidence. */
    private class Fixture {
        val events = ArrayList<String>()
        val published = ArrayList<Long>()
        val manager = Manager(events)
        val jdbc = RecordingJdbc(manager, events)
        val store: JdbcNoteSendStore
        var callbackFailure: RuntimeException? = null
        var hintFailure: RuntimeException? = null
        var notification: Notification? = null

        init {
            val tx = TransactionTemplate(manager)
            tx.timeout = 15
            store = JdbcNoteSendStore(
                jdbc,
                tx,
                NoteDeliveryHints { member, hint ->
                    assertEquals(1, manager.commits)
                    assertEquals("commit", events.last())
                    events.add("publish:$member")
                    published.add(member)
                    notification = hint
                    if (hintFailure != null) {
                        throw hintFailure!!
                    }
                },
            )
        }

        fun insert(): Receipt =
            store.inTransaction(
                Supplier {
                    events.add("callback")
                    if (callbackFailure != null) {
                        throw callbackFailure!!
                    }
                    store.insert(NEW_NOTE, HINT)
                    assertTrue(manager.active)
                    assertTrue(published.isEmpty())
                    Receipt(NOTE)
                },
            )

        fun returned() {
            assertFalse(manager.active)
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
            assertFalse(TransactionSynchronizationManager.isSynchronizationActive())
        }
    }

    private class RecordingJdbc(
        private val manager: Manager,
        private val events: MutableList<String>,
    ) : JdbcTemplate() {
        val calls = ArrayList<Call>()
        var rowKind = "present"
        var failEvent: String? = null
        var failure: RuntimeException? = null

        override fun update(sql: String, vararg args: Any?): Int {
            val normalized = normalize(sql)
            val event = if (normalized.startsWith("INSERT INTO pawbridge_community.private_note_members")) {
                "member-insert"
            } else if (normalized.startsWith("INSERT INTO pawbridge_community.private_notes(")) {
                "note"
            } else if (normalized.startsWith("INSERT INTO pawbridge_community.private_note_mailboxes(")) {
                "mailboxes"
            } else {
                "request"
            }
            record(sql, args, event)
            return if (event == "mailboxes") 2 else 1
        }

        override fun <T : Any?> queryForObject(sql: String, type: Class<T>, vararg args: Any?): T? {
            val normalized = normalize(sql)
            record(sql, args, "query")
            val requested: Class<*> = type
            if (requested == java.lang.Boolean::class.java) {
                return type.cast(true)
            }
            if (normalized.contains("count(*)")) {
                return type.cast(9L)
            }
            return type.cast(args[0])
        }

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any?> query(sql: String, mapper: RowMapper<T>, vararg args: Any?): List<T> {
            record(sql, args, "query")
            if (rowKind == "absent") {
                return listOf()
            }
            try {
                val row = mock(ResultSet::class.java)
                whenever(row.getObject("note_id", UUID::class.java)).thenReturn(if (rowKind == "null") null else NOTE)
                whenever(row.getString("request_hash")).thenReturn(if (rowKind == "null") null else "stored-hash")
                whenever(row.getString("direction")).thenReturn("INBOX")
                whenever(row.getObject("sender_id")).thenReturn(if (rowKind == "null") null else 8L)
                return listOf(mapper.mapRow(row, 0) as T)
            } catch (failure: SQLException) {
                throw AssertionError("Recording mapper failed", failure)
            }
        }

        fun record(sql: String, args: Array<out Any?>, event: String) {
            assertTrue(manager.active)
            calls.add(Call(normalize(sql), args.clone()))
            events.add(event)
            if (event == failEvent) {
                throw failure!!
            }
        }

        private fun normalize(sql: String): String = sql.replace(Regex("\\s+"), " ").trim()
    }

    private class Manager(private val events: MutableList<String>) : AbstractPlatformTransactionManager() {
        var active = false
        var commits = 0
        var rollbacks = 0
        var beginFailure: RuntimeException? = null
        var commitFailure: RuntimeException? = null

        override fun doGetTransaction(): Any = Any()

        override fun doBegin(transaction: Any, definition: TransactionDefinition) {
            assertEquals(15, definition.timeout)
            assertFalse(definition.isReadOnly)
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
        val NOTE: UUID = UUID.fromString("f2900000-0000-0000-0000-000000000001")
        val REQUEST: UUID = UUID.fromString("f2900000-0000-0000-0000-000000000002")
        val PARENT: UUID = UUID.fromString("f2900000-0000-0000-0000-000000000003")
        val NOW: Instant = Instant.parse("2028-02-29T15:00:00.123456789Z")
        val EXPIRY: Instant = Instant.parse("2029-02-28T15:00:00.123456789Z")
        const val MEMBER_INSERT =
            "INSERT INTO pawbridge_community.private_note_members(member_id) VALUES (?) ON CONFLICT DO NOTHING"
        const val MEMBER_LOCK =
            "SELECT member_id FROM pawbridge_community.private_note_members WHERE member_id = ? FOR UPDATE"
        const val NOTE_INSERT =
            "INSERT INTO pawbridge_community.private_notes( note_id, sender_id, recipient_id, body, reply_to, " +
                "context_type, context_id, created_at, expires_at ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
        const val MAILBOX_INSERT =
            "INSERT INTO pawbridge_community.private_note_mailboxes( member_id, note_id, direction, read_at, " +
                "created_at ) VALUES (?, ?, 'SENT', ?, ?), (?, ?, 'INBOX', NULL, ?)"
        const val REQUEST_INSERT =
            "INSERT INTO pawbridge_community.private_note_requests( sender_id, request_id, request_hash, note_id, " +
                "created_at ) VALUES (?, ?, ?, ?, ?)"
        val HINT = Notification(NOTE, "PRIVATE_NOTE", 7L, " raw ", NOW, false, "/notes/$NOTE")
        val NEW_NOTE = NoteSendStore.NewNote(
            NOTE, 7L, 8L, "raw body\n", PARENT, "REPORT", 17L, REQUEST, "private-hash", NOW, EXPIRY,
        )

        fun assertCall(f: Fixture, index: Int, sql: String, vararg arguments: Any?) {
            assertEquals(sql, f.jdbc.calls[index].sql)
            assertArrayEquals(arrayOf(*arguments), f.jdbc.calls[index].arguments)
        }
    }
}
