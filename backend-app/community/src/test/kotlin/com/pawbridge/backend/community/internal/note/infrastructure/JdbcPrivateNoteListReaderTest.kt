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
import org.junit.jupiter.params.provider.Arguments
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
import java.util.UUID
import java.util.stream.Stream

class JdbcPrivateNoteListReaderTest {

    @ParameterizedTest
    @MethodSource("queryInputs")
    fun `상자와 즐겨찾기와 쪽 번호와 null 가능한 열 개짜리 행이 있을 때__개수를 센 뒤 목록을 읽으면__같은 시각과 long 오프셋을 별도의 읽기 전용 트랜잭션으로 바인딩한다`(
        box: String,
        favorites: Boolean,
        page: Int,
        note: StoredNote,
    ) {
        // given
        val fixture = Fixture()
        fixture.jdbc.rows = listOf(note)

        // when
        val counted = fixture.reader.count(7L, box, favorites, NOW)
        val activeAfterCount = fixture.transactions.active
        val listed = fixture.reader.read(7L, box, favorites, page, NOW)

        // then
        assertEquals(5_000_000_000L, counted)
        assertFalse(activeAfterCount)
        assertEquals(listOf(note), listed)
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
    fun `소유자 범위의 결과가 비어 있을 때__목록을 읽으면__추가 개수나 조회 없이 커밋 뒤 빈 목록을 돌려준다`() {
        // given
        val fixture = Fixture()

        // when
        val listed = fixture.reader.read(7L, "INBOX", false, 0, NOW)

        // then
        assertEquals(listOf<StoredNote>(), listed)
        assertEquals(listOf("begin", "list", "commit"), fixture.events)
        assertEquals(1, fixture.jdbc.calls.size)
        assertFalse(fixture.transactions.active)
    }

    @ParameterizedTest
    @MethodSource("representativeFailures")
    fun `시작 질의 또는 커밋 실패 뒤 복구될 때__개수를 세거나 목록을 읽으면__0이나 빈 값이나 사용자 예외 감싸기 없이 같은 예외를 전파한다`(
        operation: Operation,
        phase: FailurePhase,
    ) {
        // given
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

        // when
        val thrown = assertThrows(RuntimeException::class.java) { invoke(fixture.reader, operation) }

        // then
        assertSame(failure, thrown)
        assertEquals(if (phase == FailurePhase.BEGIN) 0 else 1, fixture.jdbc.calls.size)
        assertEquals(if (phase == FailurePhase.QUERY) 1 else 0, fixture.transactions.rollbacks)
        assertFalse(fixture.transactions.active)
        fixture.transactions.startFailure = null
        fixture.transactions.completionFailure = null
        fixture.jdbc.failure = null

        // when
        val recovered = invoke(fixture.reader, operation)

        // then
        assertEquals(
            if (operation == Operation.COUNT) fixture.jdbc.count else fixture.jdbc.rows,
            recovered,
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
