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
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
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
    fun `일별 가입 행이 듬성듬성하고 개수가 long 최대값일 때__일별로 조회하면__0 채움이나 시계 없이 원본 행을 돌려주고 날짜를 로컬 날짜로 바인딩한다`() {
        // given
        val fixture = Fixture(Instant.EPOCH)
        val start = LocalDate.of(2024, 2, 27)
        val end = LocalDate.of(2024, 2, 29)
        val raw = listOf(DailySignupStats(start, Long.MAX_VALUE), DailySignupStats(end, 2L))
        fixture.jdbc.results.add(raw)

        // when
        val result = fixture.query.daily(start, end)

        // then
        assertEquals(raw, result)
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
    fun `시작일이 종료일보다 늦을 때__일별로 조회하면__범위를 그대로 SQL에 넘기고 빈 결과를 돌려준다`() {
        // given
        val fixture = Fixture(Instant.EPOCH)
        val start = LocalDate.of(2024, 3, 1)
        val end = start.minusDays(1)
        fixture.jdbc.results.add(listOf())

        // when
        val result = fixture.query.daily(start, end)

        // then
        assertEquals(listOf<DailySignupStats>(), result)
        assertEquals(listOf(Range(start, end)), fixture.jdbc.ranges)
        assertEquals(1, fixture.transactions.commits)
        assertFalse(fixture.transactions.active)
    }

    @ParameterizedTest
    @MethodSource("seoulDays")
    fun `서울 날짜 경계의 시각과 기간별 조회 건수가 서로 다를 때__기간별로 조회하면__읽기 전용 트랜잭션 하나에서 시계를 한 번만 읽고 각 포함 범위를 채운다`(
        instant: Instant,
        today: LocalDate,
    ) {
        // given
        val fixture = Fixture(instant)
        fixture.jdbc.results.add(listOf(DailySignupStats(today, 10L)))
        fixture.jdbc.results.add(listOf(DailySignupStats(today, 11L)))
        fixture.jdbc.results.add(listOf(DailySignupStats(today, 12L)))
        fixture.jdbc.results.add(listOf(DailySignupStats(today, 13L)))

        // when
        val result = fixture.query.periods()

        // then
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
    fun `시작이나 두 번째 조회나 커밋이 실패할 때__기간별로 조회하면__부분 응답이나 재시도 없이 원래 예외를 전파한다`(
        phase: FailurePhase,
    ) {
        // given
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

        // when
        val thrown = assertThrows(RuntimeException::class.java) { fixture.query.periods() }

        // then
        assertSame(failure, thrown)
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
                    val rows = signupRow(row)
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
        /** In-memory ResultSet exposing only the two signup columns; any other access fails the test. */
        private fun signupRow(row: DailySignupStats): ResultSet {
            val handler = InvocationHandler { _, method, args ->
                when {
                    method.name == "getObject" && args[0] == "signup_date" -> row.date
                    method.name == "getLong" && args[0] == "signup_count" -> row.count
                    else -> throw AssertionError("Unexpected ResultSet call: ${method.name}")
                }
            }
            return Proxy.newProxyInstance(
                ResultSet::class.java.classLoader,
                arrayOf<Class<*>>(ResultSet::class.java),
                handler,
            ) as ResultSet
        }

        @JvmStatic
        fun seoulDays(): Stream<Arguments> = Stream.of(
            Arguments.of(Instant.parse("2024-02-29T14:59:59.999999Z"), LocalDate.of(2024, 2, 29)),
            Arguments.of(Instant.parse("2024-02-29T15:00:00Z"), LocalDate.of(2024, 3, 1)),
        )
    }
}
