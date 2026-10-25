package com.pawbridge.backend.community.internal.note.infrastructure

import com.pawbridge.backend.community.internal.note.application.PrivateNoteReader.StoredNote
import io.mockk.every
import io.mockk.mockk
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
    fun `null 가능한 열 개짜리 행이 있을 때__쪽지를 찾으면__소유자 UUID와 엄격한 시각을 바인딩하고 원시 값을 하나의 읽기 전용 트랜잭션에서 매핑한다`(
        note: StoredNote,
    ) {
        // given
        val fixture = Fixture()
        fixture.jdbc.rows = listOf(note)

        // when
        val found = fixture.reader.find(7L, NOTE_ID, NOW)

        // then
        assertEquals(Optional.of(note), found)
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
    fun `소유자 범위의 행이 없을 때__쪽지를 찾으면__두 번째 질의 없이 커밋 뒤 빈 값을 돌려준다`() {
        // given
        val fixture = Fixture()

        // when
        val found = fixture.reader.find(7L, NOTE_ID, NOW)

        // then
        assertEquals(Optional.empty<StoredNote>(), found)
        assertEquals(listOf("begin", "find", "commit"), fixture.events)
        assertEquals(1, fixture.jdbc.calls.size)
        assertFalse(fixture.transactions.active)
    }

    @ParameterizedTest
    @EnumSource(FailurePhase::class)
    fun `시작 질의 또는 커밋 실패 뒤 복구될 때__쪽지를 찾으면__빈 값이나 사용자 예외 감싸기 없이 같은 예외를 전파한다`(
        phase: FailurePhase,
    ) {
        // given
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

        // when
        val thrown = assertThrows(RuntimeException::class.java) { fixture.reader.find(7L, NOTE_ID, NOW) }

        // then
        assertSame(failure, thrown)
        assertEquals(if (phase == FailurePhase.BEGIN) 0 else 1, fixture.jdbc.calls.size)
        assertEquals(if (phase == FailurePhase.QUERY) 1 else 0, fixture.transactions.rollbacks)
        assertFalse(fixture.transactions.active)
        fixture.transactions.startFailure = null
        fixture.transactions.completionFailure = null
        fixture.jdbc.failure = null

        // when
        val recovered = fixture.reader.find(7L, NOTE_ID, NOW)

        // then
        assertEquals(Optional.of(fixture.jdbc.rows.first()), recovered)
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
                val row = mockk<ResultSet>()
                try {
                    every { row.getObject("note_id", UUID::class.java) } returns value.noteId
                    every { row.getObject("sender_id") } returns value.senderId
                    every { row.getObject("recipient_id") } returns value.recipientId
                    every { row.getString("body") } returns value.body
                    every { row.getString("context_type") } returns value.contextType
                    every { row.getObject("context_id") } returns value.contextId
                    every { row.getTimestamp("created_at") } returns Timestamp.from(value.createdAt)
                    every { row.getString("direction") } returns value.direction
                    every { row.getTimestamp("read_at") } returns
                        (if (value.readAt == null) null else Timestamp.from(value.readAt))
                    every { row.getBoolean("favorite") } returns value.favorite
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
