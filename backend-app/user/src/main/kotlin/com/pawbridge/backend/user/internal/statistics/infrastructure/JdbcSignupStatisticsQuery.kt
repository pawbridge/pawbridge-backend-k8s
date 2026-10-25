package com.pawbridge.backend.user.internal.statistics.infrastructure

import com.pawbridge.backend.user.internal.statistics.application.SignupStatisticsQuery
import com.pawbridge.backend.user.internal.statistics.application.SignupStatisticsQuery.DailySignupStats
import com.pawbridge.backend.user.internal.statistics.application.SignupStatisticsQuery.SignupPeriods
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.support.TransactionOperations
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

class JdbcSignupStatisticsQuery(
    private val jdbc: JdbcOperations,
    private val readOnlyTransaction: TransactionOperations,
    private val clock: Clock,
) : SignupStatisticsQuery {

    override fun daily(startDate: LocalDate, endDate: LocalDate): List<DailySignupStats> =
        readOnlyTransaction.execute { _ -> query(startDate, endDate) }!!

    override fun periods(): SignupPeriods =
        readOnlyTransaction.execute { _ ->
            val today = LocalDate.now(clock.withZone(SEOUL))
            val last7DaysStart = today.minusDays(6)
            val last30DaysStart = today.minusDays(29)
            val thisMonthStart = today.withDayOfMonth(1)
            val todayRaw = query(today, today)
            val last7DaysRaw = query(last7DaysStart, today)
            val last30DaysRaw = query(last30DaysStart, today)
            val thisMonthRaw = query(thisMonthStart, today)
            SignupPeriods(
                fill(today, today, todayRaw),
                fill(last7DaysStart, today, last7DaysRaw),
                fill(last30DaysStart, today, last30DaysRaw),
                fill(thisMonthStart, today, thisMonthRaw),
            )
        }!!

    private fun query(startDate: LocalDate, endDate: LocalDate): List<DailySignupStats> =
        jdbc.query(
            DAILY_SQL,
            RowMapper { rows, _ ->
                DailySignupStats(rows.getObject("signup_date", LocalDate::class.java), rows.getLong("signup_count"))
            },
            startDate,
            endDate,
        )

    private companion object {
        // The trailing newline matches the former Java text block; unit tests compare the SQL text exactly.
        private val DAILY_SQL = """
            SELECT CAST(created_at + INTERVAL '9 hours' AS DATE) AS signup_date, count(*) AS signup_count
            FROM pawbridge_user.users
            WHERE CAST(created_at + INTERVAL '9 hours' AS DATE) BETWEEN ? AND ?
            GROUP BY CAST(created_at + INTERVAL '9 hours' AS DATE)
            ORDER BY CAST(created_at + INTERVAL '9 hours' AS DATE)
        """.trimIndent() + "\n"

        private val SEOUL = ZoneId.of("Asia/Seoul")

        private fun fill(startDate: LocalDate, endDate: LocalDate, rows: List<DailySignupStats>): List<DailySignupStats> {
            val counts = rows.associate { stats -> stats.date to stats.count }
            val result = ArrayList<DailySignupStats>()
            var date = startDate
            while (!date.isAfter(endDate)) {
                result.add(DailySignupStats(date, counts[date] ?: 0L))
                date = date.plusDays(1)
            }
            return result
        }
    }
}
