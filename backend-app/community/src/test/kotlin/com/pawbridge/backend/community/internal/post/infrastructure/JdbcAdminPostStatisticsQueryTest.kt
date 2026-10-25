package com.pawbridge.backend.community.internal.post.infrastructure

import com.pawbridge.backend.community.internal.post.application.AdminPostStatisticsQuery.BoardTypeStats
import com.pawbridge.backend.community.internal.post.application.AdminPostStatisticsQuery.DailyPostStats
import com.pawbridge.backend.community.internal.post.application.AdminPostStatisticsQuery.PostPeriodStatsResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementSetter
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.ArrayDeque
import java.util.Deque
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.Stream

class JdbcAdminPostStatisticsQueryTest {

    @Test
    fun `날짜가 듬성한 일별 행과 정렬되지 않은 게시판 행이 있을 때__기간을 읽으면__하나의 반복 읽기 트랜잭션 안에서 채우거나 정렬하지 않고 전날 개수를 분리한다`() {
        // given
        val fixture = Fixture()
        fixture.jdbc.results.add(
            listOf(row(END.minusDays(3), 3), row(END.minusDays(2), Long.MAX_VALUE), row(END, 2)),
        )
        fixture.jdbc.results.add(
            listOf(
                mapOf("board_type" to "REPORT", "post_count" to 2L),
                mapOf("board_type" to "ADOPTION", "post_count" to Long.MAX_VALUE),
            ),
        )

        // when
        val result = fixture.query.period(END.minusDays(2), END)

        // then
        assertEquals(
            PostPeriodStatsResponse(
                END.minusDays(2),
                END,
                listOf(DailyPostStats(END.minusDays(2), Long.MAX_VALUE), DailyPostStats(END, 2)),
                3,
                listOf(BoardTypeStats("REPORT", 2), BoardTypeStats("ADOPTION", Long.MAX_VALUE)),
            ),
            result,
        )
        assertEquals(
            listOf(
                Range(END.minusDays(3).atStartOfDay(), END.plusDays(1).atStartOfDay()),
                Range(END.minusDays(2).atStartOfDay(), END.plusDays(1).atStartOfDay()),
            ),
            fixture.jdbc.ranges,
        )
        assertEquals(1, fixture.samples.get())
        assertEquals(1, fixture.transactions.commits)
        assertEquals(listOf(TransactionDefinition.ISOLATION_REPEATABLE_READ), fixture.transactions.isolations)
        assertTrue(fixture.jdbc.sql.first().contains("GROUP BY CAST(created_at + INTERVAL '9 hours' AS DATE)"))
        assertTrue(fixture.jdbc.sql.first().contains("ORDER BY CAST(created_at + INTERVAL '9 hours' AS DATE)"))
        assertFalse(fixture.jdbc.sql.last().contains("ORDER BY"))
        assertTrue(
            fixture.jdbc.sql.stream().allMatch { sql ->
                sql.contains("pawbridge_community.posts") && sql.contains("deleted_at IS NULL")
            },
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["UTC", "Asia/Seoul"])
    fun `서울 자정과 JVM 시간대가 주어질 때__오늘과 기간을 읽으면__기존의 서로 다른 하루 정의와 UTC 캘린더 바인딩을 쓴다`(
        zone: String,
    ) {
        // given
        val old = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(zone))
            val fixture = Fixture()
            fixture.jdbc.results.add(listOf(mapOf("post_count" to Long.MAX_VALUE)))
            fixture.jdbc.results.add(listOf())
            fixture.jdbc.results.add(listOf())

            // when
            val todayCount = fixture.query.today()
            val todayRanges = ArrayList(fixture.jdbc.ranges)
            val todayIsolations = ArrayList(fixture.transactions.isolations)
            val period = fixture.query.period(END, END)

            // then
            assertEquals(Long.MAX_VALUE, todayCount)
            val today = if (zone == "UTC") END.minusDays(1) else END
            assertEquals(
                listOf(Range(today.atStartOfDay(), today.plusDays(1).atStartOfDay())),
                todayRanges,
            )
            assertEquals(listOf(TransactionDefinition.ISOLATION_DEFAULT), todayIsolations)
            assertEquals(PostPeriodStatsResponse(END, END, listOf(), 0, listOf()), period)
            assertEquals(2, fixture.samples.get())
            assertEquals(2, fixture.transactions.commits)
        } finally {
            TimeZone.setDefault(old)
        }
    }

    @Test
    fun `게시글이 없는 366일 포함 범위일 때__읽으면__허용하고 빈 목록을 돌려준다`() {
        // given
        val fixture = Fixture()
        fixture.jdbc.results.add(listOf())
        fixture.jdbc.results.add(listOf())

        // when
        val result = fixture.query.period(END.minusDays(365), END)

        // then
        assertEquals(listOf<DailyPostStats>(), result.daily)
        assertEquals(0, result.previousDayCount)
        assertEquals(listOf<BoardTypeStats>(), result.byBoardType)
        assertEquals(2, fixture.jdbc.ranges.size)
        assertEquals(1, fixture.transactions.commits)
    }

    @ParameterizedTest
    @MethodSource("invalidRanges")
    fun `기간이 없거나 뒤집혔거나 미래이거나 367일일 때__읽으면__SQL 전에 거부한다`(
        start: LocalDate?,
        end: LocalDate?,
    ) {
        // given
        val fixture = Fixture()

        // when
        val failure = assertThrows(ResponseStatusException::class.java) { fixture.query.period(start, end) }

        // then
        assertEquals(400, failure.statusCode.value())
        assertEquals("오늘까지 최대 366일의 올바른 조회 기간이 필요합니다.", failure.reason)
        assertTrue(fixture.jdbc.ranges.isEmpty())
        assertEquals(1, fixture.transactions.rollbacks)
        assertFalse(fixture.transactions.active)
    }

    @ParameterizedTest
    @EnumSource(Failure::class)
    fun `시작이나 두 번째 조회나 커밋이 실패할 때__기간을 읽으면__부분 결과나 재시도나 시작 전 시계 조회 없이 전파한다`(
        phase: Failure,
    ) {
        // given
        val fixture = Fixture()
        val failure: RuntimeException = when (phase) {
            Failure.BEGIN -> CannotCreateTransactionException("private-begin")
            Failure.SECOND_QUERY -> DataAccessResourceFailureException("private-query")
            Failure.COMMIT -> TransactionSystemException("private-commit")
        }
        fixture.jdbc.results.add(listOf())
        fixture.jdbc.results.add(listOf())
        if (phase == Failure.BEGIN) {
            fixture.transactions.beginFailure = failure
        } else if (phase == Failure.COMMIT) {
            fixture.transactions.commitFailure = failure
        } else {
            fixture.jdbc.failure = failure
        }

        // when
        val thrown = assertThrows(RuntimeException::class.java) { fixture.query.period(END, END) }

        // then
        assertSame(failure, thrown)
        assertEquals(if (phase == Failure.BEGIN) 0 else 2, fixture.jdbc.ranges.size)
        assertEquals(if (phase == Failure.BEGIN) 0 else 1, fixture.samples.get())
        assertEquals(if (phase == Failure.SECOND_QUERY) 1 else 0, fixture.transactions.rollbacks)
        assertFalse(fixture.transactions.active)
    }

    enum class Failure { BEGIN, SECOND_QUERY, COMMIT }

    private data class Range(val start: LocalDateTime, val end: LocalDateTime)

    private class Fixture {
        val transactions = RecordingTransactions()
        val jdbc = RecordingJdbc(transactions)
        val samples = AtomicInteger()
        val query: JdbcAdminPostStatisticsQuery

        init {
            val today = TransactionTemplate(transactions)
            today.isReadOnly = true
            today.timeout = 3
            val period = TransactionTemplate(transactions)
            period.isReadOnly = true
            period.timeout = 3
            period.isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
            query = JdbcAdminPostStatisticsQuery(
                jdbc,
                today,
                period,
                object : Clock() {
                    override fun getZone(): ZoneId = ZoneOffset.UTC

                    override fun withZone(zone: ZoneId): Clock =
                        object : Clock() {
                            override fun getZone(): ZoneId = zone

                            override fun withZone(value: ZoneId): Clock = throw UnsupportedOperationException()

                            override fun instant(): Instant {
                                assertTrue(transactions.active)
                                samples.incrementAndGet()
                                return Instant.parse("2024-02-29T15:00:00Z")
                            }
                        }

                    override fun instant(): Instant =
                        throw AssertionError("The original operation chooses its zone first")
                },
            )
        }
    }

    private class RecordingJdbc(private val transactions: RecordingTransactions) : JdbcTemplate() {
        val results: Deque<List<Map<String, Any?>>> = ArrayDeque()
        val ranges = ArrayList<Range>()
        val sql = ArrayList<String>()
        var failure: RuntimeException? = null

        override fun <T : Any?> query(statement: String, setter: PreparedStatementSetter?, mapper: RowMapper<T>): List<T> {
            assertTrue(transactions.active)
            sql.add(statement)
            try {
                val call = StatementCall(statement)
                setter!!.setValues(recordingStatement(call))
                ranges.add(
                    Range(
                        (call.values[1] as Timestamp).toLocalDateTime(),
                        (call.values[2] as Timestamp).toLocalDateTime(),
                    ),
                )
                if (failure != null && ranges.size == 2) {
                    throw failure!!
                }
                val mapped = ArrayList<T>()
                var index = 0
                for (row in results.removeFirst()) {
                    val values = FakeRow()
                    values.longs["post_count"] = row["post_count"] as Long
                    values.objects["post_date"] = row["post_date"]
                    values.strings["board_type"] = row["board_type"] as String?
                    mapped.add(mapper.mapRow(values.resultSet, index++) as T)
                }
                return mapped
            } catch (exception: SQLException) {
                throw AssertionError(exception)
            }
        }
    }

    private class RecordingTransactions : PlatformTransactionManager {
        var active = false
        var commits = 0
        var rollbacks = 0
        val isolations = ArrayList<Int>()
        var beginFailure: RuntimeException? = null
        var commitFailure: RuntimeException? = null

        override fun getTransaction(definition: TransactionDefinition?): TransactionStatus {
            assertFalse(active)
            assertTrue(definition!!.isReadOnly)
            assertEquals(3, definition.timeout)
            isolations.add(definition.isolationLevel)
            if (beginFailure != null) {
                throw beginFailure!!
            }
            active = true
            return SimpleTransactionStatus()
        }

        override fun commit(status: TransactionStatus) {
            active = false
            commits++
            if (commitFailure != null) {
                throw commitFailure!!
            }
        }

        override fun rollback(status: TransactionStatus) {
            active = false
            rollbacks++
        }
    }

    companion object {
        private val END = LocalDate.of(2024, 3, 1)

        @JvmStatic
        fun invalidRanges(): Stream<Arguments> =
            Stream.of(
                Arguments.of(null, END),
                Arguments.of(END, null),
                Arguments.of(END, END.minusDays(1)),
                Arguments.of(END, END.plusDays(1)),
                Arguments.of(END.minusDays(366), END),
            )

        private fun row(date: LocalDate, count: Long): Map<String, Any?> =
            mapOf("post_date" to date, "post_count" to count)
    }
}
