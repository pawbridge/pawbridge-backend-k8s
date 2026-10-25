package com.pawbridge.backend.community.internal.homevideo.infrastructure

import com.pawbridge.backend.community.internal.homevideo.application.HomeVideo
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.NullAndEmptySource
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

class JdbcHomeVideoReaderTest {

    @ParameterizedTest
    @NullAndEmptySource
    fun given__nullable_or_empty_metadata__when__reading__then__map_all_eleven_fields_and_bind_instant_without_local_time_conversion(
        metadata: String?,
    ) {
        val fixture = Fixture()
        val duration: Long? = if (metadata == null) null else 3_000_000_001L
        val row = mock(ResultSet::class.java)
        whenever(row.getObject("id", UUID::class.java)).thenReturn(ID)
        whenever(row.getString("video_id")).thenReturn("abcdefghijk")
        whenever(row.getBoolean("published")).thenReturn(true)
        whenever(row.getInt("position")).thenReturn(2)
        whenever(row.getTimestamp("created_at")).thenReturn(Timestamp.from(CREATED))
        whenever(row.getString("title")).thenReturn(metadata)
        whenever(row.getString("channel_title")).thenReturn(metadata)
        whenever(row.getString("thumbnail_url")).thenReturn(metadata)
        whenever(row.getObject("duration_seconds")).thenReturn(duration)
        whenever(row.getBoolean("available")).thenReturn(true)
        whenever(row.getTimestamp("checked_at")).thenReturn(Timestamp.from(CHECKED))
        fixture.jdbc.rows = listOf(row)

        assertEquals(
            listOf(HomeVideo(ID, "abcdefghijk", true, 2, CREATED, metadata, metadata, metadata, duration, true, CHECKED)),
            fixture.reader.read(CUTOFF),
        )

        assertArrayEquals(arrayOf<Any?>(Timestamp.from(CUTOFF)), fixture.jdbc.arguments)
        assertEquals(1, fixture.jdbc.queries)
        assertEquals(1, fixture.transactions.commits)
        assertEquals(0, fixture.transactions.rollbacks)
        assertFalse(fixture.transactions.active)
    }

    @Test
    fun given__no_visible_rows__when__reading__then__return_empty_list_after_read_only_completion() {
        val fixture = Fixture()

        assertEquals(listOf<HomeVideo>(), fixture.reader.read(CUTOFF))

        assertEquals(1, fixture.jdbc.queries)
        assertEquals(1, fixture.transactions.commits)
        assertEquals(0, fixture.transactions.rollbacks)
        assertFalse(fixture.transactions.active)
    }

    @Test
    fun given__query_failure_then_recovery__when__reading__then__propagate_failure_without_empty_fallback_and_release_transaction() {
        val fixture = Fixture()
        val failure = DataAccessResourceFailureException("synthetic-private-sql")
        fixture.jdbc.failure = failure

        assertSame(
            failure,
            assertThrows(DataAccessResourceFailureException::class.java) { fixture.reader.read(CUTOFF) },
        )
        assertEquals(1, fixture.transactions.rollbacks)
        assertEquals(0, fixture.transactions.commits)
        assertFalse(fixture.transactions.active)
        fixture.jdbc.failure = null

        assertEquals(listOf<HomeVideo>(), fixture.reader.read(CUTOFF))
        assertEquals(2, fixture.jdbc.queries)
        assertEquals(1, fixture.transactions.commits)
        assertFalse(fixture.transactions.active)
    }

    @Test
    fun given__transaction_start_failure_then_recovery__when__reading__then__no_query_runs_before_successful_begin() {
        val fixture = Fixture()
        val failure = CannotCreateTransactionException("synthetic-private-begin")
        fixture.transactions.startFailure = failure

        assertSame(
            failure,
            assertThrows(CannotCreateTransactionException::class.java) { fixture.reader.read(CUTOFF) },
        )
        assertEquals(0, fixture.jdbc.queries)
        assertEquals(0, fixture.transactions.commits)
        assertEquals(0, fixture.transactions.rollbacks)
        assertFalse(fixture.transactions.active)
        fixture.transactions.startFailure = null

        assertEquals(listOf<HomeVideo>(), fixture.reader.read(CUTOFF))
        assertEquals(1, fixture.jdbc.queries)
        assertEquals(1, fixture.transactions.commits)
        assertFalse(fixture.transactions.active)
    }

    @Test
    fun given__transaction_completion_failure_then_recovery__when__reading__then__no_list_is_returned_before_successful_completion() {
        val fixture = Fixture()
        val failure = TransactionSystemException("synthetic-private-completion")
        fixture.transactions.completionFailure = failure

        assertSame(
            failure,
            assertThrows(TransactionSystemException::class.java) { fixture.reader.read(CUTOFF) },
        )
        assertEquals(1, fixture.jdbc.queries)
        assertEquals(1, fixture.transactions.commits)
        assertFalse(fixture.transactions.active)
        fixture.transactions.completionFailure = null

        assertEquals(listOf<HomeVideo>(), fixture.reader.read(CUTOFF))
        assertEquals(2, fixture.jdbc.queries)
        assertEquals(2, fixture.transactions.commits)
        assertFalse(fixture.transactions.active)
    }

    private class Fixture {
        val transactions = RecordingTransactionManager()
        val jdbc = RecordingJdbc(transactions)
        val reader: JdbcHomeVideoReader

        init {
            val readOnlyTransaction = TransactionTemplate(transactions)
            readOnlyTransaction.isReadOnly = true
            readOnlyTransaction.timeout = 3
            reader = JdbcHomeVideoReader(jdbc, readOnlyTransaction)
        }
    }

    private class RecordingJdbc(private val transactions: RecordingTransactionManager) : JdbcTemplate() {
        var rows: List<ResultSet> = listOf()
        var arguments: Array<out Any?>? = null
        var queries = 0
        var failure: RuntimeException? = null

        override fun <T : Any?> query(sql: String, mapper: RowMapper<T>, vararg arguments: Any?): List<T> {
            assertTrue(
                transactions.active,
                "All JDBC mapping must complete inside the supplied read-only transaction.",
            )
            this.arguments = arguments
            queries++
            if (failure != null) {
                throw failure!!
            }
            val mapped = ArrayList<T>()
            try {
                for (index in rows.indices) {
                    mapped.add(mapper.mapRow(rows[index], index) as T)
                }
            } catch (failure: SQLException) {
                throw AssertionError("Synthetic row mapping failed", failure)
            }
            return mapped
        }
    }

    private class RecordingTransactionManager : PlatformTransactionManager {
        var active = false
        var commits = 0
        var rollbacks = 0
        var startFailure: RuntimeException? = null
        var completionFailure: RuntimeException? = null

        override fun getTransaction(definition: TransactionDefinition?): TransactionStatus {
            assertTrue(definition!!.isReadOnly)
            assertEquals(3, definition.timeout)
            if (startFailure != null) {
                throw startFailure!!
            }
            active = true
            return SimpleTransactionStatus()
        }

        override fun commit(status: TransactionStatus) {
            active = false
            commits++
            if (completionFailure != null) {
                throw completionFailure!!
            }
        }

        override fun rollback(status: TransactionStatus) {
            active = false
            rollbacks++
        }
    }

    private companion object {
        val CUTOFF: Instant = Instant.parse("2026-09-20T15:00:00.123456Z")
        val CREATED: Instant = Instant.parse("2026-09-01T16:20:30.654321Z")
        val CHECKED: Instant = Instant.parse("2026-10-19T00:00:00.123456Z")
        val ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000042")
    }
}
