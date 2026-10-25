package com.pawbridge.backend.user.internal.statistics.infrastructure

import com.pawbridge.backend.user.internal.statistics.application.SignupStatisticsQuery.DailySignupStats
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
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
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
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.Stream

class JdbcSignupStatisticsQueryTest {
    @Test
    fun given__sparse_daily_rows_and_long_count__when__reading__then__return_raw_rows_without_zero_fill_or_clock_and_bind_local_dates() {
        val fixture = Fixture(Instant.EPOCH)
        val start = LocalDate.of(2024, 2, 27)
        val end = LocalDate.of(2024, 2, 29)
        val raw = listOf(DailySignupStats(start, Long.MAX_VALUE), DailySignupStats(end, 2L))
        fixture.jdbc.results.add(raw)

        assertEquals(raw, fixture.query.daily(start, end))

        assertEquals(listOf(Range(start, end)), fixture.jdbc.ranges)
        assertEquals(0, fixture.clock.samples.get())
        assertEquals(1, fixture.transactions.commits)
        assertFalse(fixture.transactions.active)
        assertTrue(fixture.jdbc.sql!!.contains("CAST(created_at + INTERVAL '9 hours' AS DATE) BETWEEN ? AND ?"))
        assertTrue(fixture.jdbc.sql!!.contains("GROUP BY CAST(created_at + INTERVAL '9 hours' AS DATE)"))
        assertTrue(fixture.jdbc.sql!!.contains("ORDER BY CAST(created_at + INTERVAL '9 hours' AS DATE)"))
        assertFalse(fixture.jdbc.sql!!.contains("AT TIME ZONE"))
    }

    @Test
    fun given__reversed_dates__when__reading_daily__then__pass_the_range_to_sql_and_return_its_empty_result() {
        val fixture = Fixture(Instant.EPOCH)
        val start = LocalDate.of(2024, 3, 1)
        val end = start.minusDays(1)
        fixture.jdbc.results.add(listOf())

        assertEquals(listOf<DailySignupStats>(), fixture.query.daily(start, end))

        assertEquals(listOf(Range(start, end)), fixture.jdbc.ranges)
        assertEquals(1, fixture.transactions.commits)
        assertFalse(fixture.transactions.active)
    }

    @ParameterizedTest
    @MethodSource("seoulDays")
    fun given__a_seoul_day_boundary_and_independent_query_counts__when__reading_periods__then__sample_once_inside_one_read_only_transaction_and_fill_each_inclusive_range(
        instant: Instant,
        today: LocalDate,
    ) {
        val fixture = Fixture(instant)
        fixture.jdbc.results.add(listOf(DailySignupStats(today, 10L)))
        fixture.jdbc.results.add(listOf(DailySignupStats(today, 11L)))
        fixture.jdbc.results.add(listOf(DailySignupStats(today, 12L)))
        fixture.jdbc.results.add(listOf(DailySignupStats(today, 13L)))

        val result = fixture.query.periods()

        assertEquals(1, fixture.clock.samples.get())
        assertEquals(
            listOf(
                Range(today, today),
                Range(today.minusDays(6), today),
                Range(today.minusDays(29), today),
                Range(today.withDayOfMonth(1), today),
            ),
            fixture.jdbc.ranges,
        )
        assertEquals(listOf(DailySignupStats(today, 10L)), result.today)
        assertEquals(7, result.last7Days!!.size)
        assertEquals(30, result.last30Days!!.size)
        assertEquals(today.dayOfMonth, result.thisMonth!!.size)
        assertEquals(DailySignupStats(today.minusDays(6), 0L), result.last7Days!!.first())
        assertEquals(DailySignupStats(today.minusDays(29), 0L), result.last30Days!!.first())
        assertEquals(DailySignupStats(today, 11L), result.last7Days!!.last())
        assertEquals(DailySignupStats(today, 12L), result.last30Days!!.last())
        assertEquals(DailySignupStats(today, 13L), result.thisMonth!!.last())
        assertEquals(1, fixture.transactions.starts)
        assertEquals(1, fixture.transactions.commits)
        assertEquals(0, fixture.transactions.rollbacks)
        assertFalse(fixture.transactions.active)
    }

    @ParameterizedTest
    @EnumSource(FailurePhase::class)
    fun given__begin_second_query_or_commit_failure__when__reading_periods__then__propagate_the_original_failure_without_partial_response_or_retry(
        phase: FailurePhase,
    ) {
        val fixture = Fixture(Instant.EPOCH)
        val failure: RuntimeException = when (phase) {
            FailurePhase.BEGIN -> CannotCreateTransactionException("synthetic-private-begin")
            FailurePhase.SECOND_QUERY -> DataAccessResourceFailureException("synthetic-private-query")
            FailurePhase.COMMIT -> TransactionSystemException("synthetic-private-commit")
        }
        for (index in 0 until 4) {
            fixture.jdbc.results.add(listOf())
        }
        if (phase == FailurePhase.BEGIN) {
            fixture.transactions.beginFailure = failure
        } else if (phase == FailurePhase.SECOND_QUERY) {
            fixture.jdbc.failure = failure
        } else {
            fixture.transactions.commitFailure = failure
        }

        assertSame(failure, assertThrows(RuntimeException::class.java) { fixture.query.periods() })

        assertEquals(
            if (phase == FailurePhase.BEGIN) 0 else if (phase == FailurePhase.SECOND_QUERY) 2 else 4,
            fixture.jdbc.ranges.size,
        )
        assertEquals(if (phase == FailurePhase.SECOND_QUERY) 1 else 0, fixture.transactions.rollbacks)
        assertFalse(fixture.transactions.active)
    }

    enum class FailurePhase { BEGIN, SECOND_QUERY, COMMIT }

    private data class Range(val start: LocalDate, val end: LocalDate)

    private class Fixture(instant: Instant) {
        val transactions = RecordingTransactions()
        val jdbc = RecordingJdbc(transactions)
        val clock: CountingClock
        val query: JdbcSignupStatisticsQuery

        init {
            clock = CountingClock(instant, ZoneOffset.UTC, transactions, AtomicInteger())
            val readOnly = TransactionTemplate(transactions)
            readOnly.isReadOnly = true
            readOnly.timeout = 3
            query = JdbcSignupStatisticsQuery(jdbc, readOnly, clock)
        }
    }

    private class RecordingJdbc(private val transactions: RecordingTransactions) : JdbcTemplate() {
        val results = ArrayDeque<List<DailySignupStats>>()
        val ranges = ArrayList<Range>()
        var sql: String? = null
        var failure: RuntimeException? = null

        override fun <T> query(sql: String, rowMapper: RowMapper<T>, vararg args: Any?): MutableList<T> {
            assertTrue(transactions.active)
            assertEquals(2, args.size)
            this.sql = sql
            ranges.add(Range(args[0] as LocalDate, args[1] as LocalDate))
            if (failure != null && ranges.size == 2) {
                throw failure!!
            }
            val mapped = ArrayList<T>()
            try {
                var index = 0
                for (row in results.removeFirst()) {
                    val rows = mock(ResultSet::class.java)
                    `when`(rows.getObject("signup_date", LocalDate::class.java)).thenReturn(row.date)
                    `when`(rows.getLong("signup_count")).thenReturn(row.count)
                    mapped.add(rowMapper.mapRow(rows, index++)!!)
                }
            } catch (failure: SQLException) {
                throw AssertionError(failure)
            }
            return mapped
        }
    }

    private class RecordingTransactions : PlatformTransactionManager {
        var active = false
        var starts = 0
        var commits = 0
        var rollbacks = 0
        var beginFailure: RuntimeException? = null
        var commitFailure: RuntimeException? = null

        override fun getTransaction(definition: TransactionDefinition?): TransactionStatus {
            assertFalse(active)
            assertTrue(definition!!.isReadOnly)
            assertEquals(TransactionDefinition.ISOLATION_DEFAULT, definition.isolationLevel)
            assertEquals(3, definition.timeout)
            starts++
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

    private class CountingClock(
        private val value: Instant,
        private val zone: ZoneId,
        private val transactions: RecordingTransactions,
        val samples: AtomicInteger,
    ) : Clock() {
        override fun getZone(): ZoneId {
            return zone
        }

        override fun withZone(zone: ZoneId): Clock {
            return CountingClock(value, zone, transactions, samples)
        }

        override fun instant(): Instant {
            assertTrue(transactions.active)
            samples.incrementAndGet()
            return value
        }
    }

    companion object {
        @JvmStatic
        fun seoulDays(): Stream<Arguments> = Stream.of(
            Arguments.of(Instant.parse("2024-02-29T14:59:59.999999Z"), LocalDate.of(2024, 2, 29)),
            Arguments.of(Instant.parse("2024-02-29T15:00:00Z"), LocalDate.of(2024, 3, 1)),
        )
    }
}
