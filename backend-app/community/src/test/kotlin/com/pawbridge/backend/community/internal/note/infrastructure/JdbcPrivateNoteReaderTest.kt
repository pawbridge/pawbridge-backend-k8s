package com.pawbridge.backend.community.internal.note.infrastructure

import com.pawbridge.backend.community.internal.note.application.PrivateNoteReader.StoredNote
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
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
import java.util.Optional
import java.util.UUID
import java.util.stream.Stream

class JdbcPrivateNoteReaderTest {

    @ParameterizedTest
    @MethodSource("storedNotes")
    fun given__nullable_ten_column_row__when__finding__then__bind_owner_uuid_and_strict_instant_and_map_raw_values_in_one_read_only_transaction(
        note: StoredNote,
    ) {
        val fixture = Fixture()
        fixture.jdbc.rows = listOf(note)

        assertEquals(Optional.of(note), fixture.reader.find(7L, NOTE_ID, NOW))

        assertEquals(listOf("begin", "find", "commit"), fixture.events)
        assertEquals(1, fixture.jdbc.calls.size)
        val call = fixture.jdbc.calls.first()
        assertEquals(
            "SELECT n.note_id, n.sender_id, n.recipient_id, n.body, n.context_type, n.context_id," +
                " n.created_at, m.direction, m.read_at, m.favorite" +
                " FROM pawbridge_community.private_note_mailboxes m" +
                " JOIN pawbridge_community.private_notes n ON n.note_id = m.note_id" +
                " WHERE m.member_id = ? AND n.note_id = ? AND n.expires_at > ?",
            normalize(call.sql),
        )
        assertArrayEquals(arrayOf<Any?>(7L, NOTE_ID, Timestamp.from(NOW)), call.arguments)
        assertEquals(1, fixture.transactions.commits)
        assertFalse(fixture.transactions.active)
    }

    @Test
    fun given__no_owner_scoped_row__when__finding__then__return_empty_after_commit_without_second_query() {
        val fixture = Fixture()

        assertEquals(Optional.empty<StoredNote>(), fixture.reader.find(7L, NOTE_ID, NOW))

        assertEquals(listOf("begin", "find", "commit"), fixture.events)
        assertEquals(1, fixture.jdbc.calls.size)
        assertFalse(fixture.transactions.active)
    }

    @ParameterizedTest
    @EnumSource(FailurePhase::class)
    fun given__begin_query_or_commit_failure_then_recovery__when__finding__then__propagate_same_failure_without_empty_or_user_wrapping(
        phase: FailurePhase,
    ) {
        val fixture = Fixture()
        fixture.jdbc.rows = listOf(storedNotes().findFirst().orElseThrow())
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

        assertSame(
            failure,
            assertThrows(RuntimeException::class.java) { fixture.reader.find(7L, NOTE_ID, NOW) },
        )

        assertEquals(if (phase == FailurePhase.BEGIN) 0 else 1, fixture.jdbc.calls.size)
        assertEquals(if (phase == FailurePhase.QUERY) 1 else 0, fixture.transactions.rollbacks)
        assertFalse(fixture.transactions.active)
        fixture.transactions.startFailure = null
        fixture.transactions.completionFailure = null
        fixture.jdbc.failure = null

        assertEquals(Optional.of(fixture.jdbc.rows.first()), fixture.reader.find(7L, NOTE_ID, NOW))
        assertEquals(if (phase == FailurePhase.BEGIN) 1 else 2, fixture.jdbc.calls.size)
        assertFalse(fixture.transactions.active)
    }

    enum class FailurePhase { BEGIN, QUERY, COMMIT }

    private class Call(val sql: String, val arguments: Array<out Any?>)

    private class Fixture {
        val events = ArrayList<String>()
        val transactions = RecordingTransactionManager(events)
        val jdbc = RecordingJdbc(transactions, events)
        val reader: JdbcPrivateNoteReader

        init {
            val transaction = TransactionTemplate(transactions)
            transaction.isReadOnly = true
            transaction.timeout = 3
            reader = JdbcPrivateNoteReader(jdbc, transaction)
        }
    }

    private class RecordingJdbc(
        private val transactions: RecordingTransactionManager,
        private val events: MutableList<String>,
    ) : JdbcTemplate() {
        val calls = ArrayList<Call>()
        var rows: List<StoredNote> = listOf()
        var failure: RuntimeException? = null

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any?> query(sql: String, rowMapper: RowMapper<T>, vararg arguments: Any?): List<T> {
            assertTrue(transactions.active)
            events.add("find")
            calls.add(Call(sql, arguments))
            if (failure != null) {
                throw failure!!
            }
            val result = ArrayList<T>()
            for (index in rows.indices) {
                val value = rows[index]
                val row = mock(ResultSet::class.java)
                try {
                    whenever(row.getObject("note_id", UUID::class.java)).thenReturn(value.noteId)
                    whenever(row.getObject("sender_id")).thenReturn(value.senderId)
                    whenever(row.getObject("recipient_id")).thenReturn(value.recipientId)
                    whenever(row.getString("body")).thenReturn(value.body)
                    whenever(row.getString("context_type")).thenReturn(value.contextType)
                    whenever(row.getObject("context_id")).thenReturn(value.contextId)
                    whenever(row.getTimestamp("created_at")).thenReturn(Timestamp.from(value.createdAt))
                    whenever(row.getString("direction")).thenReturn(value.direction)
                    whenever(row.getTimestamp("read_at"))
                        .thenReturn(if (value.readAt == null) null else Timestamp.from(value.readAt))
                    whenever(row.getBoolean("favorite")).thenReturn(value.favorite)
                    result.add(rowMapper.mapRow(row, index) as T)
                } catch (exception: SQLException) {
                    throw AssertionError(exception)
                }
            }
            return result
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
        private val NOTE_ID = UUID(1L, 9L)

        @JvmStatic
        fun storedNotes(): Stream<StoredNote> =
            Stream.of(
                StoredNote(
                    NOTE_ID, null, 7L, " 원문\n본문 ", null, null,
                    Instant.parse("1969-12-31T23:59:59.000001Z"), "INBOX", null, false,
                ),
                StoredNote(
                    NOTE_ID, 7L, null, "", "REPORT", Long.MAX_VALUE,
                    NOW, "SENT", NOW.minusSeconds(1), true,
                ),
            )

        private fun normalize(sql: String): String = sql.replace(Regex("\\s+"), " ").trim()
    }
}
