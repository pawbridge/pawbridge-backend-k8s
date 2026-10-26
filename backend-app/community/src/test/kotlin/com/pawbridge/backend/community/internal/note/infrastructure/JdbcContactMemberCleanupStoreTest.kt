package com.pawbridge.backend.community.internal.note.infrastructure

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.function.LongConsumer

class JdbcContactMemberCleanupStoreTest {

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `채팅 설치 여부가 다르고 영향받는 쪽지가 있을 때__회원을 제거하면__원래 SQL 순서와 바인딩 ID를 지키고 배치를 커밋 힌트보다 먼저 보낸다`(
        installed: Boolean,
    ) {
        // given
        val fixture = Fixture()
        fixture.jdbc.installed = installed
        fixture.jdbc.notes = if (installed) (1..301).map { i -> UUID(17, i.toLong()) } else listOf()
        fixture.jdbc.rooms = if (installed) listOf(UUID(19, 1), UUID(19, 2)) else listOf()

        // when
        fixture.store.remove(MEMBER) { fixture.events.add("policy") }

        // then
        val calls = fixture.jdbc.calls
        assertCall(
            calls[0],
            "INSERT INTO pawbridge_community.private_note_members(member_id) VALUES (?) ON CONFLICT DO NOTHING",
            MEMBER,
        )
        assertCall(
            calls[1],
            "SELECT member_id FROM pawbridge_community.private_note_members WHERE member_id = ? FOR UPDATE",
            MEMBER,
        )
        assertEquals(3, fixture.events.indexOf("policy"))
        assertCall(
            calls[2],
            "SELECT note_id FROM pawbridge_community.private_notes WHERE sender_id = ? OR recipient_id = ? ORDER BY note_id FOR UPDATE",
            MEMBER, MEMBER,
        )
        assertCall(
            calls[3],
            "UPDATE pawbridge_community.private_note_requests SET request_hash = NULL WHERE note_id IN ( SELECT note_id FROM pawbridge_community.private_notes WHERE sender_id = ? OR recipient_id = ? )",
            MEMBER, MEMBER,
        )
        assertCall(
            calls[4],
            "UPDATE pawbridge_community.private_notes SET context_type = NULL, context_id = NULL WHERE sender_id = ? OR recipient_id = ?",
            MEMBER, MEMBER,
        )
        assertCall(calls[5], "DELETE FROM pawbridge_community.private_note_members WHERE member_id = ?", MEMBER)
        var next = 6
        if (installed) {
            for (start in listOf(0, 300)) {
                val batch = fixture.jdbc.notes.subList(start, Math.min(start + 300, 301))
                val call = calls[next++]
                assertEquals(
                    "DELETE FROM pawbridge_community.private_notes n WHERE note_id IN (" +
                        java.util.Collections.nCopies(batch.size, "?").joinToString(",") +
                        ") AND NOT EXISTS( SELECT 1 FROM pawbridge_community.private_note_mailboxes m WHERE m.note_id = n.note_id )",
                    call.sql,
                )
                assertEquals(batch, call.arguments)
            }
        }
        assertCall(calls[next++], "SELECT to_regclass('pawbridge_community.member_chat_rooms') IS NOT NULL")
        if (installed) {
            assertCall(
                calls[next++],
                "SELECT room_id FROM pawbridge_community.member_chat_room_members WHERE member_id=?",
                MEMBER,
            )
            assertCall(
                calls[next++],
                "DELETE FROM pawbridge_community.member_chat_room_members WHERE member_id=?",
                MEMBER,
            )
            assertCall(
                calls[next++],
                "DELETE FROM pawbridge_community.member_chat_requests WHERE sender_id=?",
                MEMBER,
            )
            assertCall(
                calls[next++],
                "UPDATE pawbridge_community.member_chat_messages SET sender_id=NULL WHERE sender_id=?",
                MEMBER,
            )
            assertCall(
                calls[next++],
                "UPDATE pawbridge_community.member_chat_rooms SET first_member_id=NULL WHERE first_member_id=?",
                MEMBER,
            )
            assertCall(
                calls[next++],
                "UPDATE pawbridge_community.member_chat_rooms SET second_member_id=NULL WHERE second_member_id=?",
                MEMBER,
            )
            for (room in fixture.jdbc.rooms) {
                assertCall(
                    calls[next++],
                    "DELETE FROM pawbridge_community.member_chat_rooms r WHERE room_id=? AND NOT EXISTS( SELECT 1 FROM pawbridge_community.member_chat_room_members a WHERE a.room_id=r.room_id)",
                    room,
                )
            }
        }
        assertEquals(next, calls.size)
        assertEquals(
            listOf("commit", "hint:7", "cleanup"),
            fixture.events.subList(fixture.events.size - 3, fixture.events.size),
        )
        assertEquals(1, fixture.transactions.commitAttempts)
        assertEquals(0, fixture.transactions.rollbacks)
        assertEquals(1, fixture.hints)
        assertFalse(fixture.transactions.active)
    }

    @ParameterizedTest
    @EnumSource(FailurePhase::class)
    fun `정책 쪽지 SQL 설치 질의 또는 동기 채팅 처리가 실패할 때__회원을 제거하면__예외를 전파하고 롤백하며 커밋 힌트를 부르지 않는다`(
        phase: FailurePhase,
    ) {
        // given
        val fixture = Fixture()
        fixture.jdbc.installed = true
        fixture.jdbc.notes = listOf(UUID(17, 1))
        fixture.jdbc.rooms = listOf(UUID(19, 1))
        val failure: RuntimeException =
            if (phase == FailurePhase.POLICY) {
                IllegalStateException("private policy")
            } else {
                DataAccessResourceFailureException("private SQL")
            }
        fixture.jdbc.failure = failure
        fixture.jdbc.failPrefix = when (phase) {
            FailurePhase.POLICY -> null
            FailurePhase.NOTES -> "SELECT note_id FROM pawbridge_community.private_notes"
            FailurePhase.INSTALLATION -> "SELECT to_regclass"
            FailurePhase.CHAT -> "UPDATE pawbridge_community.member_chat_messages"
        }

        // when
        val thrown = assertThrows(RuntimeException::class.java) {
            fixture.store.remove(MEMBER) {
                fixture.events.add("policy")
                if (phase == FailurePhase.POLICY) {
                    throw failure
                }
            }
        }

        // then
        assertSame(failure, thrown)
        assertEquals(
            listOf("rollback", "cleanup"),
            fixture.events.subList(fixture.events.size - 2, fixture.events.size),
        )
        assertEquals(0, fixture.transactions.commitAttempts)
        assertEquals(1, fixture.transactions.rollbacks)
        assertEquals(0, fixture.hints)
        assertFalse(fixture.transactions.active)
        if (phase == FailurePhase.POLICY) {
            assertEquals(2, fixture.jdbc.calls.size)
        } else {
            assertTrue(fixture.jdbc.calls.last().sql.startsWith(fixture.jdbc.failPrefix!!))
        }
    }

    @Test
    fun `커밋이 실패할 때__회원을 제거하면__같은 예외를 전파하고 힌트를 보내지 않는다`() {
        // given
        val fixture = Fixture()
        val failure = TransactionSystemException("private commit")
        fixture.transactions.commitFailure = failure

        // when
        val thrown = assertThrows(TransactionSystemException::class.java) {
            fixture.store.remove(MEMBER) { fixture.events.add("policy") }
        }

        // then
        assertSame(failure, thrown)
        assertEquals(1, fixture.transactions.commitAttempts)
        assertEquals(0, fixture.hints)
        assertFalse(fixture.transactions.committed)
        assertFalse(fixture.transactions.active)
    }

    @Test
    fun `커밋 뒤 힌트가 런타임 예외를 던질 때__회원을 제거하면__커밋을 유지하고 예외 종류만 기록한다`() {
        // given
        val fixture = Fixture()
        fixture.hintFailure = IllegalStateException("private hint payload")
        val logger = LoggerFactory.getLogger(JdbcContactMemberCleanupStore::class.java) as Logger
        val previousLevel = logger.level
        logger.level = Level.WARN
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        try {
            // when
            fixture.store.remove(MEMBER) { fixture.events.add("policy") }

            // then
            assertEquals(1, fixture.transactions.commitAttempts)
            assertTrue(fixture.transactions.committed)
            assertEquals(1, fixture.hints)
            assertEquals(1, appender.list.size)
            assertEquals(
                "Private note push failed (IllegalStateException); REST restoration remains available",
                appender.list.first().formattedMessage,
            )
            assertNull(appender.list.first().throwableProxy)
            assertFalse(fixture.transactions.active)
        } finally {
            logger.detachAppender(appender)
            logger.level = previousLevel
            appender.stop()
        }
    }

    enum class FailurePhase { POLICY, NOTES, INSTALLATION, CHAT }

    private class Call(val sql: String, val arguments: List<Any?>)

    private class Fixture {
        val events = ArrayList<String>()
        val transactions = RecordingTransactionManager(events)
        val jdbc = RecordingJdbc(transactions, events)
        val store: JdbcContactMemberCleanupStore
        var hintFailure: RuntimeException? = null
        var hints = 0

        init {
            val transaction = TransactionTemplate(transactions)
            transaction.timeout = 15
            store = JdbcContactMemberCleanupStore(
                jdbc,
                transaction,
                LongConsumer { memberId ->
                    assertTrue(transactions.committed)
                    hints++
                    events.add("hint:$memberId")
                    if (hintFailure != null) {
                        throw hintFailure!!
                    }
                },
            )
        }
    }

    private class RecordingJdbc(
        private val transactions: RecordingTransactionManager,
        private val events: MutableList<String>,
    ) : JdbcTemplate() {
        val calls = ArrayList<Call>()
        var notes: List<UUID> = listOf()
        var rooms: List<UUID> = listOf()
        var installed = false
        var failPrefix: String? = null
        var failure: RuntimeException? = null

        override fun update(sql: String, vararg arguments: Any?): Int {
            record(sql, arguments)
            return 0
        }

        override fun <T : Any?> queryForObject(sql: String, type: Class<T>, vararg arguments: Any?): T? {
            record(sql, arguments)
            assertEquals(java.lang.Long::class.java, type)
            return type.cast(arguments[0])
        }

        override fun <T : Any?> queryForObject(sql: String, type: Class<T>): T? {
            record(sql, arrayOf())
            assertEquals(java.lang.Boolean::class.java, type)
            return type.cast(installed)
        }

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any?> query(sql: String, mapper: RowMapper<T>, vararg arguments: Any?): List<T> {
            record(sql, arguments)
            return notes as List<T>
        }

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any?> queryForList(sql: String, type: Class<T>, vararg arguments: Any?): List<T> {
            record(sql, arguments)
            assertEquals(UUID::class.java, type)
            return rooms as List<T>
        }

        private fun record(sql: String, arguments: Array<out Any?>) {
            assertTrue(transactions.active)
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
            val normalized = sql.replace(Regex("\\s+"), " ").trim()
            calls.add(Call(normalized, listOf(*arguments)))
            events.add("sql")
            if (failPrefix != null && normalized.startsWith(failPrefix!!)) {
                throw failure!!
            }
        }
    }

    private class RecordingTransactionManager(private val events: MutableList<String>) :
        AbstractPlatformTransactionManager() {
        var active = false
        var committed = false
        var commitAttempts = 0
        var rollbacks = 0
        var commitFailure: RuntimeException? = null

        override fun doGetTransaction(): Any = Any()

        override fun doBegin(tx: Any, definition: TransactionDefinition) {
            assertEquals(15, definition.timeout)
            assertFalse(definition.isReadOnly)
            assertFalse(active)
            active = true
            events.add("begin")
        }

        override fun doCommit(status: DefaultTransactionStatus) {
            events.add("commit")
            commitAttempts++
            if (commitFailure != null) {
                throw commitFailure!!
            }
            committed = true
        }

        override fun doRollback(status: DefaultTransactionStatus) {
            events.add("rollback")
            rollbacks++
        }

        override fun doCleanupAfterCompletion(tx: Any) {
            active = false
            events.add("cleanup")
        }
    }

    private companion object {
        const val MEMBER = 7L

        fun assertCall(call: Call, sql: String, vararg arguments: Any?) {
            assertEquals(sql, call.sql)
            assertEquals(listOf(*arguments), call.arguments)
        }
    }
}
