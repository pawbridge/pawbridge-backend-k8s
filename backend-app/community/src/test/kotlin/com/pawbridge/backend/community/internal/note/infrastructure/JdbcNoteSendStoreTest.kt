package com.pawbridge.backend.community.internal.note.infrastructure

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.pawbridge.backend.community.internal.note.application.NoteDeliveryHints
import com.pawbridge.backend.community.internal.note.application.NoteNotifications.Notification
import com.pawbridge.backend.community.internal.note.application.NoteSendRejectedException
import com.pawbridge.backend.community.internal.note.application.NoteSendStore
import com.pawbridge.backend.community.internal.note.application.SendPrivateNote.Receipt
import io.mockk.every
import io.mockk.mockk
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
    fun `순서가 뒤집혔거나 같은 회원 쌍일 때__콜백에서 잠그면__서로 다른 ID마다 오름차순으로 삽입하고 잠근다`(
        first: Long,
        second: Long,
    ) {
        // given
        val f = Fixture()

        // when
        val receipt = f.store.inTransaction(
            Supplier {
                f.store.lockMembers(first, second)
                Receipt(NOTE)
            },
        )

        // then
        assertEquals(Receipt(NOTE), receipt)
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
    fun `정상 행이거나 null 행이거나 행이 없을 때__발송 사실을 읽으면__원시 투영과 경계와 방향과 포함 비교 매개변수 SQL을 유지한다`(
        rows: String,
    ) {
        // given
        val f = Fixture()
        f.jdbc.rowKind = rows

        // when
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

        // then
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
    fun `새 쪽지와 알림이 있을 때__콜백이 삽입하면__문장 세 개를 바인딩하고 커밋 뒤에 수신자만 발행한다`() {
        // given
        val f = Fixture()

        // when
        val receipt = f.insert()

        // then
        assertEquals(Receipt(NOTE), receipt)
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
    fun `시작 콜백 삽입 또는 커밋이 실패할 때__발송을 실행하면__재시도나 발행 없이 전파하고 동기화를 해제한다`(
        phase: Failure,
    ) {
        // given
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

        // when
        val thrown = assertThrows(RuntimeException::class.java) { f.insert() }

        // then
        assertSame(failure, thrown)
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
    fun `커밋 뒤 비공개 발행이 런타임 예외를 낼 때__힌트를 보내면__저장 성공을 유지하고 예외 객체 없이 타입만 기록한다`() {
        // given
        val f = Fixture()
        f.hintFailure = IllegalStateException("Bearer private-token body hash", SQLException("private SQL"))
        val logger = LoggerFactory.getLogger(JdbcNoteSendStore::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        try {
            // when
            val receipt = f.insert()

            // then
            assertEquals(Receipt(NOTE), receipt)
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
                val row = mockk<ResultSet>()
                every { row.getObject("note_id", UUID::class.java) } returns (if (rowKind == "null") null else NOTE)
                every { row.getString("request_hash") } returns (if (rowKind == "null") null else "stored-hash")
                every { row.getString("direction") } returns "INBOX"
                every { row.getObject("sender_id") } returns (if (rowKind == "null") null else 8L)
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
