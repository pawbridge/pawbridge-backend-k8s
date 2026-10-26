package com.pawbridge.backend.community.internal.homevideo.infrastructure

import com.pawbridge.backend.community.internal.homevideo.application.HomeVideo
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
import org.junit.jupiter.params.provider.NullAndEmptySource
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
    fun `메타데이터가 null이거나 비었을 때__읽으면__열한 필드를 모두 매핑하고 시각을 지역 시간 변환 없이 바인딩한다`(
        metadata: String?,
    ) {
        // given
        val fixture = Fixture()
        val duration: Long? = if (metadata == null) null else 3_000_000_001L
        val row = mockk<ResultSet>()

        every { row.getObject("id", UUID::class.java) } returns ID
        every { row.getString("video_id") } returns "abcdefghijk"
        every { row.getBoolean("published") } returns true
        every { row.getInt("position") } returns 2
        every { row.getTimestamp("created_at") } returns Timestamp.from(CREATED)
        every { row.getString("title") } returns metadata
        every { row.getString("channel_title") } returns metadata
        every { row.getString("thumbnail_url") } returns metadata
        every { row.getObject("duration_seconds") } returns duration
        every { row.getBoolean("available") } returns true
        every { row.getTimestamp("checked_at") } returns Timestamp.from(CHECKED)
        fixture.jdbc.rows = listOf(row)

        // when
        val result = fixture.reader.read(CUTOFF)

        // then
        assertEquals(
            listOf(HomeVideo(ID, "abcdefghijk", true, 2, CREATED, metadata, metadata, metadata, duration, true, CHECKED)),
            result,
        )
        assertArrayEquals(arrayOf<Any?>(Timestamp.from(CUTOFF)), fixture.jdbc.arguments)
        assertEquals(1, fixture.jdbc.queries)
        assertEquals(1, fixture.transactions.commits)
        assertEquals(0, fixture.transactions.rollbacks)
        assertFalse(fixture.transactions.active)
    }

    @Test
    fun `보이는 행이 없을 때__읽으면__읽기 전용 완료 뒤에 빈 목록을 돌려준다`() {
        // given
        val fixture = Fixture()

        // when
        val result = fixture.reader.read(CUTOFF)

        // then
        assertEquals(listOf<HomeVideo>(), result)
        assertEquals(1, fixture.jdbc.queries)
        assertEquals(1, fixture.transactions.commits)
        assertEquals(0, fixture.transactions.rollbacks)
        assertFalse(fixture.transactions.active)
    }

    @Test
    fun `질의가 실패했다가 복구될 때__읽으면__빈 목록으로 대체하지 않고 전파하며 트랜잭션을 놓는다`() {
        // given
        val fixture = Fixture()
        val failure = DataAccessResourceFailureException("synthetic-private-sql")
        fixture.jdbc.failure = failure

        // when
        val thrown = assertThrows(DataAccessResourceFailureException::class.java) { fixture.reader.read(CUTOFF) }

        // then
        assertSame(failure, thrown)
        assertEquals(1, fixture.transactions.rollbacks)
        assertEquals(0, fixture.transactions.commits)
        assertFalse(fixture.transactions.active)

        // given
        fixture.jdbc.failure = null

        // when
        val recovered = fixture.reader.read(CUTOFF)

        // then
        assertEquals(listOf<HomeVideo>(), recovered)
        assertEquals(2, fixture.jdbc.queries)
        assertEquals(1, fixture.transactions.commits)
        assertFalse(fixture.transactions.active)
    }

    @Test
    fun `트랜잭션 시작이 실패했다가 복구될 때__읽으면__시작에 성공하기 전에는 질의하지 않는다`() {
        // given
        val fixture = Fixture()
        val failure = CannotCreateTransactionException("synthetic-private-begin")
        fixture.transactions.startFailure = failure

        // when
        val thrown = assertThrows(CannotCreateTransactionException::class.java) { fixture.reader.read(CUTOFF) }

        // then
        assertSame(failure, thrown)
        assertEquals(0, fixture.jdbc.queries)
        assertEquals(0, fixture.transactions.commits)
        assertEquals(0, fixture.transactions.rollbacks)
        assertFalse(fixture.transactions.active)

        // given
        fixture.transactions.startFailure = null

        // when
        val recovered = fixture.reader.read(CUTOFF)

        // then
        assertEquals(listOf<HomeVideo>(), recovered)
        assertEquals(1, fixture.jdbc.queries)
        assertEquals(1, fixture.transactions.commits)
        assertFalse(fixture.transactions.active)
    }

    @Test
    fun `트랜잭션 완료가 실패했다가 복구될 때__읽으면__완료에 성공하기 전에는 목록을 돌려주지 않는다`() {
        // given
        val fixture = Fixture()
        val failure = TransactionSystemException("synthetic-private-completion")
        fixture.transactions.completionFailure = failure

        // when
        val thrown = assertThrows(TransactionSystemException::class.java) { fixture.reader.read(CUTOFF) }

        // then
        assertSame(failure, thrown)
        assertEquals(1, fixture.jdbc.queries)
        assertEquals(1, fixture.transactions.commits)
        assertFalse(fixture.transactions.active)

        // given
        fixture.transactions.completionFailure = null

        // when
        val recovered = fixture.reader.read(CUTOFF)

        // then
        assertEquals(listOf<HomeVideo>(), recovered)
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
