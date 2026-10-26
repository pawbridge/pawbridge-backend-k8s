package com.pawbridge.backend.community.internal.note.infrastructure

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.pawbridge.backend.community.internal.note.application.NoteChangeHints
import com.pawbridge.backend.community.internal.note.application.NoteDeletion
import com.pawbridge.backend.community.internal.note.application.NoteFavoriteMutation
import com.pawbridge.backend.community.internal.note.application.NoteReadMutation
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
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
import java.util.stream.Stream

/**
 * Mark-as-read, set-favorite and delete run the same transaction script: member row insert and lock, active guard,
 * two ownership checks around the note lock, then their own write(s), and a push hint only after the commit.
 * The first three tests pin each script's exact SQL, arguments and clock samples. The remaining tests run the
 * shared failure, missing-note and hint-failure contract against all three.
 */
class JdbcNoteMutationsTest {

    @ParameterizedTest
    @ValueSource(ints = [0, 1])
    fun `갱신된 사서함이 0건이거나 1건일 때__읽음 처리를 하면__서로 다른 시각 세 개를 바인딩하고 매니저 커밋 뒤에만 힌트를 보낸다`(
        affected: Int,
    ) {
        // given
        val fixture = Fixture(READ)
        fixture.jdbc.rows["update"] = affected

        // when
        fixture.run()

        // then
        assertEquals(
            listOf(
                "begin", "insert", "member-lock", "guard", "clock:1", "find:1", "note-lock",
                "clock:2", "find:2", "clock:3", "update", "commit", "hint:7", "cleanup",
            ),
            fixture.events,
        )
        assertEquals(
            listOf(INSERT, MEMBER_LOCK, FIND, NOTE_LOCK, FIND, READ_UPDATE),
            fixture.jdbc.calls.map { it.sql },
        )
        assertCommonArguments(fixture)
        assertArrayEquals(
            arrayOf<Any?>(Timestamp.from(FIRST.plusSeconds(2)), 7L, NOTE),
            fixture.jdbc.calls[5].arguments,
        )
        assertCommitted(fixture, samples = 3)
    }

    @ParameterizedTest
    @CsvSource("false, 0", "true, 1")
    fun `즐겨찾기 값과 갱신된 사서함이 0건이거나 1건일 때__즐겨찾기를 설정하면__서로 다른 시각 두 개를 바인딩하고 매니저 커밋 뒤에만 힌트를 보낸다`(
        favorite: Boolean,
        affected: Int,
    ) {
        // given
        val fixture = Fixture(favoriteSubject(favorite))
        fixture.jdbc.rows["update"] = affected

        // when
        fixture.run()

        // then
        assertEquals(
            listOf(
                "begin", "insert", "member-lock", "guard", "clock:1", "find:1", "note-lock",
                "clock:2", "find:2", "update", "commit", "hint:7", "cleanup",
            ),
            fixture.events,
        )
        assertEquals(
            listOf(INSERT, MEMBER_LOCK, FIND, NOTE_LOCK, FIND, FAVORITE_UPDATE),
            fixture.jdbc.calls.map { it.sql },
        )
        assertCommonArguments(fixture)
        assertArrayEquals(arrayOf<Any?>(favorite, 7L, NOTE), fixture.jdbc.calls[5].arguments)
        assertCommitted(fixture, samples = 2)
    }

    @ParameterizedTest
    @CsvSource("0, 0", "1, 1")
    fun `삭제된 행이 0개 또는 1개일 때__쪽지를 삭제하면__서로 다른 두 시각을 바인딩하고 우편함을 고아 쪽지보다 먼저 지우며 커밋 뒤에만 힌트를 보낸다`(
        mailboxRows: Int,
        noteRows: Int,
    ) {
        // given
        val fixture = Fixture(DELETE)
        fixture.jdbc.rows["delete-mailbox"] = mailboxRows
        fixture.jdbc.rows["delete-orphan"] = noteRows

        // when
        fixture.run()

        // then
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
        assertCommonArguments(fixture)
        assertArrayEquals(arrayOf<Any?>(7L, NOTE), fixture.jdbc.calls[5].arguments)
        assertArrayEquals(arrayOf<Any?>(NOTE), fixture.jdbc.calls[6].arguments)
        assertCommitted(fixture, samples = 2)
    }

    @ParameterizedTest(name = "{0} 확인 {1}")
    @MethodSource("subjectsWithMissingAt")
    fun `소유 쪽지가 0행 쪽지 잠금 앞이나 뒤에서 사라졌을 때__변경하면__쓰기나 힌트 없이 롤백한다`(
        subject: Subject,
        missingAt: Int,
    ) {
        // given
        val fixture = Fixture(subject)
        fixture.jdbc.found.clear()
        if (missingAt == 2) {
            fixture.jdbc.found.add(true)
        }
        fixture.jdbc.found.add(false)
        fixture.jdbc.noteLockRows = 0

        // when
        assertThrows(subject.missingOwnedNote) { fixture.run() }

        // then
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

    @ParameterizedTest(name = "{0} {1} 실패")
    @MethodSource("subjectsWithPhase")
    fun `시작 회원 삽입 가드 잠금 쓰기 또는 커밋이 실패할 때__변경하면__재시도나 힌트 없이 예외를 전파한다`(
        subject: Subject,
        phase: String,
    ) {
        // given
        val fixture = Fixture(subject)
        val failure: RuntimeException = when (phase) {
            "begin" -> CannotCreateTransactionException("synthetic begin")
            "insert" -> DataIntegrityViolationException("synthetic member CHECK")
            "guard" -> IllegalStateException("synthetic guard")
            "commit" -> TransactionSystemException("synthetic commit")
            else -> DataAccessResourceFailureException("synthetic SQL")
        }
        when (phase) {
            "begin" -> fixture.manager.beginFailure = failure
            "commit" -> fixture.manager.commitFailure = failure
            "guard" -> fixture.guardFailure = failure
            else -> {
                fixture.jdbc.failEvent = phase
                fixture.jdbc.failure = failure
            }
        }

        // when
        val thrown = assertThrows(RuntimeException::class.java) { fixture.run() }

        // then
        assertSame(failure, thrown)
        val last = subject.stages.indexOf(phase)
        val expected = ArrayList(subject.stages.subList(0, last + 1))
        if (phase != "begin" && phase != "commit") {
            expected.add("rollback")
        }
        if (phase != "begin") {
            expected.add("cleanup")
        }
        assertEquals(expected, fixture.events)
        assertTrue(fixture.hinted.isEmpty())
        assertEquals(if (phase == "commit") 1 else 0, fixture.manager.commits)
        assertEquals(if (phase == "begin" || phase == "commit") 0 else 1, fixture.manager.rollbacks)
        fixture.assertReturned()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("subjects")
    fun `커밋 뒤 비공개 힌트가 런타임 예외를 낼 때__알림을 보내면__성공을 유지하고 비공개 메시지 없이 타입만 기록한다`(
        subject: Subject,
    ) {
        // given
        val fixture = Fixture(subject)
        fixture.hintFailure = IllegalStateException("Bearer synthetic-private-token", SQLException("synthetic-private-SQL"))
        val logger = LoggerFactory.getLogger(subject.mutationType) as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        try {
            // when
            assertDoesNotThrow { fixture.run() }

            // then
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

    private fun assertCommonArguments(fixture: Fixture) {
        assertArrayEquals(arrayOf<Any?>(7L), fixture.jdbc.calls[0].arguments)
        assertArrayEquals(arrayOf<Any?>(7L), fixture.jdbc.calls[1].arguments)
        assertArrayEquals(arrayOf<Any?>(7L, NOTE, Timestamp.from(FIRST)), fixture.jdbc.calls[2].arguments)
        assertArrayEquals(arrayOf<Any?>(NOTE), fixture.jdbc.calls[3].arguments)
        assertArrayEquals(arrayOf<Any?>(7L, NOTE, Timestamp.from(FIRST.plusSeconds(1))), fixture.jdbc.calls[4].arguments)
    }

    private fun assertCommitted(fixture: Fixture, samples: Int) {
        assertEquals(1, fixture.guards)
        assertEquals(samples, fixture.samples)
        assertEquals(listOf(7L), fixture.hinted)
        assertEquals(1, fixture.manager.commits)
        assertEquals(0, fixture.manager.rollbacks)
        fixture.assertReturned()
    }

    /** One of the three mutations, reduced to what the shared tests need. */
    class Subject(
        private val name: String,
        val mutationType: Class<*>,
        val missingOwnedNote: Class<out RuntimeException>,
        /** Events from begin to commit; the failure test fails each of them in turn. */
        val stages: List<String>,
        val create: (JdbcTemplate, TransactionTemplate, NoteChangeHints) -> (Runnable, Supplier<Instant>) -> Unit,
    ) {
        override fun toString(): String = name
    }

    private class Call(val sql: String, val arguments: Array<out Any?>)

    private class Fixture(subject: Subject) {
        val events = ArrayList<String>()
        val hinted = ArrayList<Long>()
        val manager = SynchronizingManager(events)
        val jdbc = RecordingJdbc(manager, events)
        private val invoke: (Runnable, Supplier<Instant>) -> Unit
        var guards = 0
        var samples = 0
        var guardFailure: RuntimeException? = null
        var hintFailure: RuntimeException? = null

        init {
            val transaction = TransactionTemplate(manager)
            transaction.timeout = 15
            invoke = subject.create(
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

        fun run() {
            val now = Supplier<Instant> {
                samples++
                events.add("clock:$samples")
                FIRST.plusSeconds((samples - 1).toLong())
            }
            invoke(
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

        /** Affected rows per write event; every write affects one row unless a test says otherwise. */
        val rows = HashMap<String, Int>()
        var finds = 0
        var noteLockRows = 1
        var failEvent: String? = null
        var failure: RuntimeException? = null

        override fun update(sql: String, vararg arguments: Any?): Int {
            val statement = normalize(sql)
            val event = when {
                statement.startsWith("INSERT") -> "insert"
                statement.startsWith("UPDATE") -> "update"
                statement.startsWith("DELETE FROM pawbridge_community.private_note_mailboxes") -> "delete-mailbox"
                else -> "delete-orphan"
            }
            record(sql, arguments, event)
            return if (event == "insert") 0 else rows.getOrDefault(event, 1)
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
                return listOf(mapper.mapRow(mockk<ResultSet>(), 0) as T)
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

    companion object {
        private val NOTE: UUID = UUID.fromString("f2600000-0000-0000-0000-000000000001")
        private val FIRST: Instant = Instant.parse("2031-01-01T00:00:00.123456Z")
        private const val INSERT =
            "INSERT INTO pawbridge_community.private_note_members(member_id) VALUES (?) ON CONFLICT DO NOTHING"
        private const val MEMBER_LOCK =
            "SELECT member_id FROM pawbridge_community.private_note_members WHERE member_id = ? FOR UPDATE"
        private const val FIND =
            "SELECT 1 FROM pawbridge_community.private_note_mailboxes m JOIN pawbridge_community.private_notes n ON n.note_id = m.note_id WHERE m.member_id = ? AND n.note_id = ? AND n.expires_at > ?"
        private const val NOTE_LOCK = "SELECT note_id FROM pawbridge_community.private_notes WHERE note_id = ? FOR UPDATE"
        private const val READ_UPDATE =
            "UPDATE pawbridge_community.private_note_mailboxes SET read_at = COALESCE(read_at, ?) WHERE member_id = ? AND note_id = ? AND direction = 'INBOX'"
        private const val FAVORITE_UPDATE =
            "UPDATE pawbridge_community.private_note_mailboxes SET favorite = ? WHERE member_id = ? AND note_id = ?"
        private const val DELETE_MAILBOX =
            "DELETE FROM pawbridge_community.private_note_mailboxes WHERE member_id = ? AND note_id = ?"
        private const val DELETE_ORPHAN =
            "DELETE FROM pawbridge_community.private_notes n WHERE note_id = ? AND NOT EXISTS( SELECT 1 FROM pawbridge_community.private_note_mailboxes m WHERE m.note_id = n.note_id )"

        private val CHECKS = listOf("begin", "insert", "member-lock", "guard", "clock:1", "find:1", "note-lock", "clock:2", "find:2")

        private val READ = Subject(
            name = "읽음",
            mutationType = JdbcNoteReadMutation::class.java,
            missingOwnedNote = NoteReadMutation.MissingOwnedNote::class.java,
            stages = CHECKS + listOf("clock:3", "update", "commit"),
            create = { jdbc, transaction, hints ->
                val mutation = JdbcNoteReadMutation(jdbc, transaction, hints)
                ({ guard, now -> mutation.read(7L, NOTE, guard, now) })
            },
        )

        private fun favoriteSubject(favorite: Boolean) = Subject(
            name = "즐겨찾기",
            mutationType = JdbcNoteFavoriteMutation::class.java,
            missingOwnedNote = NoteFavoriteMutation.MissingOwnedNote::class.java,
            stages = CHECKS + listOf("update", "commit"),
            create = { jdbc, transaction, hints ->
                val mutation = JdbcNoteFavoriteMutation(jdbc, transaction, hints)
                ({ guard, now -> mutation.set(7L, NOTE, favorite, guard, now) })
            },
        )

        private val DELETE = Subject(
            name = "삭제",
            mutationType = JdbcNoteDeletion::class.java,
            missingOwnedNote = NoteDeletion.MissingOwnedNote::class.java,
            stages = CHECKS + listOf("delete-mailbox", "delete-orphan", "commit"),
            create = { jdbc, transaction, hints ->
                val mutation = JdbcNoteDeletion(jdbc, transaction, hints)
                ({ guard, now -> mutation.delete(7L, NOTE, guard, now) })
            },
        )

        private val SUBJECTS = listOf(READ, favoriteSubject(false), DELETE)

        @JvmStatic
        fun subjects(): Stream<Arguments> = SUBJECTS.stream().map { Arguments.of(it) }

        @JvmStatic
        fun subjectsWithMissingAt(): Stream<Arguments> =
            SUBJECTS.stream().flatMap { subject -> Stream.of(1, 2).map { Arguments.of(subject, it) } }

        /** Every stage that can fail, except the read-only ones (member lock, clocks, existence checks). */
        @JvmStatic
        fun subjectsWithPhase(): Stream<Arguments> = SUBJECTS.stream().flatMap { subject ->
            subject.stages
                .filter { it == "begin" || it == "insert" || it == "guard" || it == "note-lock" || it == "commit" ||
                    it == "update" || it.startsWith("delete-") }
                .stream()
                .map { Arguments.of(subject, it) }
        }
    }
}
