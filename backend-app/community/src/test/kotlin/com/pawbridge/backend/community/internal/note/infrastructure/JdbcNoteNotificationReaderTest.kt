package com.pawbridge.backend.community.internal.note.infrastructure

import com.pawbridge.backend.community.internal.note.application.NoteNotificationReader.Row
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when` as whenever
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.stream.Stream

class JdbcNoteNotificationReaderTest {

    @ParameterizedTest
    @MethodSource("cursors")
    fun given__optional_cursor_and_nullable_four_column_rows__when__listing_then_counting_unread__then__bind_owner_scoped_cursor_and_same_instant_in_separate_read_only_transactions(
        cursor: UUID?,
    ) {
        val fixture = Fixture()
        fixture.jdbc.rows = listOf(
            Row(UUID(1L, 2L), Long.MAX_VALUE, NOW, NOW.minusSeconds(1)),
            Row(UUID(1L, 1L), null, Instant.parse("1969-12-31T23:59:59.000001Z"), null),
        )

        assertEquals(fixture.jdbc.rows, fixture.reader.read(7L, cursor, NOW))
        assertFalse(fixture.transactions.active)
        assertEquals(5_000_000_000L, fixture.reader.unread(7L, NOW))

        assertEquals(listOf("begin", "list", "commit", "begin", "unread", "commit"), fixture.events)
        assertEquals(2, fixture.jdbc.calls.size)
        val page = fixture.jdbc.calls[0]
        val cursorClause =
            if (cursor == null) {
                ""
            } else {
                " AND (m.created_at, m.note_id) < (" +
                    " SELECT created_at, note_id FROM pawbridge_community.private_note_mailboxes" +
                    " WHERE member_id = ? AND note_id = ? AND direction = 'INBOX' )"
            }
        assertEquals(
            "SELECT n.note_id, n.sender_id, n.created_at, m.read_at" + JOIN +
                "WHERE m.member_id = ? AND m.direction = 'INBOX' AND n.expires_at > ?" +
                cursorClause + " ORDER BY m.created_at DESC, m.note_id DESC LIMIT 21",
            normalize(page.sql),
        )
        assertArrayEquals(
            if (cursor == null) {
                arrayOf<Any?>(7L, Timestamp.from(NOW))
            } else {
                arrayOf<Any?>(7L, Timestamp.from(NOW), 7L, cursor)
            },
            page.arguments,
        )
        val count = fixture.jdbc.calls[1]
        assertEquals(
            "SELECT count(*)" + JOIN +
                "WHERE m.member_id = ? AND m.direction = 'INBOX' AND m.read_at IS NULL AND n.expires_at > ?",
            normalize(count.sql),
        )
        assertArrayEquals(arrayOf<Any?>(7L, Timestamp.from(NOW)), count.arguments)
        assertEquals(2, fixture.transactions.commits)
        assertFalse(fixture.transactions.active)
    }

    @Test
    fun given__null_unread_result__when__counting__then__fail_without_zero_fallback() {
        val fixture = Fixture()
        fixture.jdbc.count = null

        assertThrows(NullPointerException::class.java) { fixture.reader.unread(7L, NOW) }

        assertEquals(listOf("begin", "unread", "commit"), fixture.events)
        assertEquals(1, fixture.jdbc.calls.size)
        assertFalse(fixture.transactions.active)
    }

    @ParameterizedTest
    @MethodSource("representativeFailures")
    fun given__begin_query_or_commit_failure_then_recovery__when__reading__then__propagate_same_failure_without_empty_zero_or_user_wrapping(
        operation: Operation,
        phase: FailurePhase,
    ) {
        val fixture = Fixture()
        val failure: RuntimeException = when (phase) {
            FailurePhase.BEGIN -> CannotCreateTransactionException("private begin")
            FailurePhase.QUERY -> DataAccessResourceFailureException("private SQL")
            FailurePhase.COMMIT -> TransactionSystemException("private commit")
        }
        when (phase) {
            FailurePhase.BEGIN -> fixture.transactions.startFailure = failure
            FailurePhase.QUERY -> fixture.jdbc.failure = failure
            FailurePhase.COMMIT -> fixture.transactions.completionFailure = failure
        }

        assertSame(failure, assertThrows(RuntimeException::class.java) { invoke(fixture.reader, operation) })

        assertEquals(if (phase == FailurePhase.BEGIN) 0 else 1, fixture.jdbc.calls.size)
        assertEquals(if (phase == FailurePhase.QUERY) 1 else 0, fixture.transactions.rollbacks)
        assertFalse(fixture.transactions.active)
        fixture.transactions.startFailure = null
        fixture.transactions.completionFailure = null
        fixture.jdbc.failure = null

        assertEquals(
            if (operation == Operation.LIST) fixture.jdbc.rows else fixture.jdbc.count,
            invoke(fixture.reader, operation),
        )
        assertEquals(if (phase == FailurePhase.BEGIN) 1 else 2, fixture.jdbc.calls.size)
        assertFalse(fixture.transactions.active)
    }

    enum class Operation { LIST, UNREAD }

    enum class FailurePhase { BEGIN, QUERY, COMMIT }

    private class Call(val sql: String, val arguments: Array<out Any?>)

    private class Fixture {
        val events = ArrayList<String>()
        val transactions = RecordingTransactionManager(events)
        val jdbc = RecordingJdbc(transactions, events)
        val reader: JdbcNoteNotificationReader

        init {
            val transaction = TransactionTemplate(transactions)
            transaction.isReadOnly = true
            transaction.timeout = 3
            reader = JdbcNoteNotificationReader(jdbc, transaction)
        }
    }

    private class RecordingJdbc(
        private val transactions: RecordingTransactionManager,
        private val events: MutableList<String>,
    ) : JdbcTemplate() {
        val calls = ArrayList<Call>()
        var rows: List<Row> = listOf()
        var count: Long? = 5_000_000_000L
        var failure: RuntimeException? = null

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any?> query(sql: String, rowMapper: RowMapper<T>, vararg arguments: Any?): List<T> {
            record("list", sql, arguments)
            val result = ArrayList<T>()
            for (index in rows.indices) {
                val value = rows[index]
                val row = mock(ResultSet::class.java)
                try {
                    whenever(row.getObject("note_id", UUID::class.java)).thenReturn(value.noteId)
                    whenever(row.getObject("sender_id")).thenReturn(value.senderId)
                    whenever(row.getTimestamp("created_at")).thenReturn(Timestamp.from(value.createdAt))
                    whenever(row.getTimestamp("read_at"))
                        .thenReturn(if (value.readAt == null) null else Timestamp.from(value.readAt))
                    result.add(rowMapper.mapRow(row, index) as T)
                } catch (exception: SQLException) {
                    throw AssertionError(exception)
                }
            }
            return result
        }

        override fun <T : Any?> queryForObject(sql: String, requiredType: Class<T>, vararg arguments: Any?): T? {
            record("unread", sql, arguments)
            return requiredType.cast(count)
        }

        private fun record(operation: String, sql: String, arguments: Array<out Any?>) {
            assertTrue(transactions.active)
            events.add(operation)
            calls.add(Call(sql, arguments))
            if (failure != null) {
                throw failure!!
            }
        }
    }

    private class RecordingTransactionManager(private val events: MutableList<String>) : PlatformTransactionManager {
        var active = false
        var commits = 0
        var rollbacks = 0
        var startFailure: RuntimeException? = null
        var completionFailure: RuntimeException? = null

        override fun getTransaction(definition: TransactionDefinition?): TransactionStatus {
            assertFalse(active)
            assertTrue(definition!!.isReadOnly)
            assertEquals(3, definition.timeout)
            events.add("begin")
            if (startFailure != null) {
                throw startFailure!!
            }
            active = true
            return SimpleTransactionStatus()
        }

        override fun commit(status: TransactionStatus) {
            active = false
            commits++
            events.add("commit")
            if (completionFailure != null) {
                throw completionFailure!!
            }
        }

        override fun rollback(status: TransactionStatus) {
            active = false
            rollbacks++
            events.add("rollback")
        }
    }

    companion object {
        private val NOW = Instant.parse("2031-01-01T00:00:00.123456Z")
        private const val JOIN =
            " FROM pawbridge_community.private_note_mailboxes m" +
                " JOIN pawbridge_community.private_notes n ON n.note_id = m.note_id "

        @JvmStatic
        fun cursors(): Stream<Arguments> =
            Stream.of(Arguments.of(*arrayOf<Any?>(null)), Arguments.of(UUID(1L, 9L)))

        @JvmStatic
        fun representativeFailures(): Stream<Arguments> =
            Stream.of(
                Arguments.of(Operation.LIST, FailurePhase.BEGIN),
                Arguments.of(Operation.LIST, FailurePhase.QUERY),
                Arguments.of(Operation.UNREAD, FailurePhase.COMMIT),
            )

        private fun invoke(reader: JdbcNoteNotificationReader, operation: Operation): Any? =
            if (operation == Operation.LIST) reader.read(7L, null, NOW) else reader.unread(7L, NOW)

        private fun normalize(sql: String): String = sql.replace(Regex("\\s+"), " ").trim()
    }
}
