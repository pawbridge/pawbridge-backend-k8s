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
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when` as whenever
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
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.ArrayDeque
import java.util.Calendar
import java.util.Deque
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.Stream

class JdbcAdminPostStatisticsQueryTest {

    @Test
    fun given__sparse_daily_rows_and_unordered_board_rows__when__reading_period__then__separate_previous_day_without_filling_or_sorting_inside_one_rr_transaction() {
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

        val result = fixture.query.period(END.minusDays(2), END)

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
    fun given__a_seoul_midnight_and_jvm_zone__when__reading_today_and_period__then__use_the_original_distinct_day_definitions_and_utc_calendar_binding(
        zone: String,
    ) {
        val old = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(zone))
            val fixture = Fixture()
            fixture.jdbc.results.add(listOf(mapOf("post_count" to Long.MAX_VALUE)))
            assertEquals(Long.MAX_VALUE, fixture.query.today())
            val today = if (zone == "UTC") END.minusDays(1) else END
            assertEquals(
                listOf(Range(today.atStartOfDay(), today.plusDays(1).atStartOfDay())),
                fixture.jdbc.ranges,
            )
            assertEquals(listOf(TransactionDefinition.ISOLATION_DEFAULT), fixture.transactions.isolations)
            fixture.jdbc.results.add(listOf())
            fixture.jdbc.results.add(listOf())
            assertEquals(
                PostPeriodStatsResponse(END, END, listOf(), 0, listOf()),
                fixture.query.period(END, END),
            )
            assertEquals(2, fixture.samples.get())
            assertEquals(2, fixture.transactions.commits)
        } finally {
            TimeZone.setDefault(old)
        }
    }

    @Test
    fun given__an_inclusive_366_day_range_without_posts__when__reading__then__allow_it_and_return_empty_lists() {
        val fixture = Fixture()
        fixture.jdbc.results.add(listOf())
        fixture.jdbc.results.add(listOf())

        val result = fixture.query.period(END.minusDays(365), END)

        assertEquals(listOf<DailyPostStats>(), result.daily)
        assertEquals(0, result.previousDayCount)
        assertEquals(listOf<BoardTypeStats>(), result.byBoardType)
        assertEquals(2, fixture.jdbc.ranges.size)
        assertEquals(1, fixture.transactions.commits)
    }

    @ParameterizedTest
    @MethodSource("invalidRanges")
    fun given__a_missing_reversed_future_or_367_day_range__when__reading__then__reject_before_sql(
        start: LocalDate?,
        end: LocalDate?,
    ) {
        val fixture = Fixture()

        val failure = assertThrows(ResponseStatusException::class.java) { fixture.query.period(start, end) }

        assertEquals(400, failure.statusCode.value())
        assertEquals("오늘까지 최대 366일의 올바른 조회 기간이 필요합니다.", failure.reason)
        assertTrue(fixture.jdbc.ranges.isEmpty())
        assertEquals(1, fixture.transactions.rollbacks)
        assertFalse(fixture.transactions.active)
    }

    @ParameterizedTest
    @EnumSource(Failure::class)
    fun given__begin_second_query_or_commit_failure__when__reading_period__then__propagate_without_partial_results_retry_or_clock_before_begin(
        phase: Failure,
    ) {
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

        assertSame(failure, assertThrows(RuntimeException::class.java) { fixture.query.period(END, END) })

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
                val prepared = mock(PreparedStatement::class.java)
                val timestamps = HashMap<Int, Timestamp>()
                doAnswer { call ->
                    assertEquals("UTC", call.getArgument<Calendar>(2).timeZone.id)
                    timestamps[call.getArgument(0)] = call.getArgument(1)
                    null
                }.`when`(prepared).setTimestamp(anyInt(), any(Timestamp::class.java), any(Calendar::class.java))
                setter!!.setValues(prepared)
                ranges.add(Range(timestamps[1]!!.toLocalDateTime(), timestamps[2]!!.toLocalDateTime()))
                if (failure != null && ranges.size == 2) {
                    throw failure!!
                }
                val mapped = ArrayList<T>()
                var index = 0
                for (row in results.removeFirst()) {
                    val values = mock(ResultSet::class.java)
                    whenever(values.getLong("post_count")).thenReturn(row["post_count"] as Long?)
                    whenever(values.getObject("post_date", LocalDate::class.java)).thenReturn(row["post_date"] as LocalDate?)
                    whenever(values.getString("board_type")).thenReturn(row["board_type"] as String?)
                    mapped.add(mapper.mapRow(values, index++) as T)
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
