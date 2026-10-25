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

class JdbcPrivateNoteListReaderTest {

    @ParameterizedTest
    @MethodSource("queryInputs")
    fun given__box_favorite_page_and_nullable_ten_column_row__when__counting_then_listing__then__bind_same_instant_and_long_offset_with_separate_read_only_transactions(
        box: String,
        favorites: Boolean,
        page: Int,
        note: StoredNote,
    ) {
        val fixture = Fixture()
        fixture.jdbc.rows = listOf(note)

        assertEquals(5_000_000_000L, fixture.reader.count(7L, box, favorites, NOW))
        assertFalse(fixture.transactions.active)
        assertEquals(listOf(note), fixture.reader.read(7L, box, favorites, page, NOW))

        assertEquals(listOf("begin", "count", "commit", "begin", "list", "commit"), fixture.events)
        assertEquals(2, fixture.jdbc.calls.size)
        val count = fixture.jdbc.calls[0]
        assertEquals("SELECT count(*)" + JOIN + FILTER, normalize(count.sql))
        assertArrayEquals(arrayOf<Any?>(7L, box, favorites, Timestamp.from(NOW)), count.arguments)
        val list = fixture.jdbc.calls[1]
        assertEquals(
            "SELECT n.note_id, n.sender_id, n.recipient_id, n.body, n.context_type, n.context_id," +
                " n.created_at, m.direction, m.read_at, m.favorite" + JOIN + FILTER +
                " ORDER BY m.created_at DESC, m.note_id DESC LIMIT 10 OFFSET ?",
            normalize(list.sql),
        )
        assertArrayEquals(
            arrayOf<Any?>(7L, box, favorites, Timestamp.from(NOW), page.toLong() * 10),
            list.arguments,
        )
        assertEquals(2, fixture.transactions.commits)
        assertFalse(fixture.transactions.active)
    }

    @Test
    fun given__empty_owner_scoped_result__when__listing__then__return_empty_after_commit_without_extra_count_or_find() {
        val fixture = Fixture()

        assertEquals(listOf<StoredNote>(), fixture.reader.read(7L, "INBOX", false, 0, NOW))

        assertEquals(listOf("begin", "list", "commit"), fixture.events)
        assertEquals(1, fixture.jdbc.calls.size)
        assertFalse(fixture.transactions.active)
    }

    @ParameterizedTest
    @MethodSource("representativeFailures")
    fun given__begin_query_or_commit_failure_then_recovery__when__counting_or_listing__then__propagate_same_failure_without_zero_empty_or_user_wrapping(
        operation: Operation,
        phase: FailurePhase,
    ) {
        val fixture = Fixture()
        fixture.jdbc.rows = listOf(StoredNote(UUID(23L, 1L), 8L, 7L, " 原文 ", null, null, NOW, "INBOX", null, false))
        val failure: RuntimeException = when (phase) {
            FailurePhase.BEGIN -> CannotCreateTransactionException("private begin")
            FailurePhase.QUERY -> DataAccessResourceFailureException("private query")
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
            if (operation == Operation.COUNT) fixture.jdbc.count else fixture.jdbc.rows,
            invoke(fixture.reader, operation),
        )
        assertEquals(if (phase == FailurePhase.BEGIN) 1 else 2, fixture.jdbc.calls.size)
        assertFalse(fixture.transactions.active)
    }

    enum class Operation { COUNT, LIST }

    enum class FailurePhase { BEGIN, QUERY, COMMIT }

    private class Call(val sql: String, val arguments: Array<out Any?>)

    private class Fixture {
        val events = ArrayList<String>()
        val transactions = RecordingTransactionManager(events)
        val jdbc = RecordingJdbc(transactions, events)
        val reader: JdbcPrivateNoteListReader

        init {
            val transaction = TransactionTemplate(transactions)
            transaction.isReadOnly = true
            transaction.timeout = 3
            reader = JdbcPrivateNoteListReader(jdbc, transaction)
        }
    }

    private class RecordingJdbc(
        private val transactions: RecordingTransactionManager,
        private val events: MutableList<String>,
    ) : JdbcTemplate() {
        val calls = ArrayList<Call>()
        var rows: List<StoredNote> = listOf()
        var count: Long? = 5_000_000_000L
        var failure: RuntimeException? = null

        override fun <T : Any?> queryForObject(sql: String, requiredType: Class<T>, vararg arguments: Any?): T? {
            record("count", sql, arguments)
            return requiredType.cast(count)
        }

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
        private const val FILTER =
            "WHERE m.member_id = ? AND m.direction = ? AND (NOT ? OR m.favorite) AND n.expires_at > ?"

        @JvmStatic
        fun queryInputs(): Stream<Arguments> =
            Stream.of(
                Arguments.of(
                    "INBOX", false, 0,
                    StoredNote(
                        UUID(23L, 1L), null, 7L, " 原文\n본문 ", null, null,
                        Instant.parse("1969-12-31T23:59:59.000001Z"), "INBOX", null, false,
                    ),
                ),
                Arguments.of(
                    "SENT", true, 10000,
                    StoredNote(
                        UUID(23L, 2L), 7L, null, " 보낸 원문 ", "REPORT", Long.MAX_VALUE,
                        NOW, "SENT", NOW.minusSeconds(1), true,
                    ),
                ),
            )

        @JvmStatic
        fun representativeFailures(): Stream<Arguments> =
            Stream.of(
                Arguments.of(Operation.COUNT, FailurePhase.BEGIN),
                Arguments.of(Operation.LIST, FailurePhase.QUERY),
                Arguments.of(Operation.LIST, FailurePhase.COMMIT),
            )

        private fun invoke(reader: JdbcPrivateNoteListReader, operation: Operation): Any? =
            if (operation == Operation.COUNT) {
                reader.count(7L, "INBOX", false, NOW)
            } else {
                reader.read(7L, "INBOX", false, 0, NOW)
            }

        private fun normalize(sql: String): String = sql.replace(Regex("\\s+"), " ").trim()
    }
}
