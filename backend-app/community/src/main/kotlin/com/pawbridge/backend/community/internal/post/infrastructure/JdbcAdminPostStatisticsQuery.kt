package com.pawbridge.backend.community.internal.post.infrastructure

import com.pawbridge.backend.community.internal.post.application.AdminPostStatisticsQuery
import com.pawbridge.backend.community.internal.post.application.AdminPostStatisticsQuery.BoardTypeStats
import com.pawbridge.backend.community.internal.post.application.AdminPostStatisticsQuery.DailyPostStats
import com.pawbridge.backend.community.internal.post.application.AdminPostStatisticsQuery.PostPeriodStatsResponse
import java.sql.Timestamp
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Calendar
import java.util.TimeZone
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.PreparedStatementSetter
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.support.TransactionOperations
import org.springframework.web.server.ResponseStatusException

class JdbcAdminPostStatisticsQuery(
    private val jdbc: JdbcOperations,
    private val todayTransaction: TransactionOperations,
    private val periodTransaction: TransactionOperations,
    private val clock: Clock,
) : AdminPostStatisticsQuery {

    override fun today(): Long =
        todayTransaction.execute {
            val start = LocalDate.now(clock.withZone(ZoneId.systemDefault())).atStartOfDay()
            query(
                TODAY_SQL,
                start,
                start.plusDays(1),
                RowMapper { rows, _ -> rows.getLong("post_count") },
            ).first()
        }!!

    override fun period(startDate: LocalDate?, endDate: LocalDate?): PostPeriodStatsResponse =
        periodTransaction.execute {
            if (startDate == null ||
                endDate == null ||
                startDate.isAfter(endDate) ||
                endDate.isAfter(LocalDate.now(clock.withZone(SEOUL))) ||
                ChronoUnit.DAYS.between(startDate, endDate) >= 366
            ) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "오늘까지 최대 366일의 올바른 조회 기간이 필요합니다.")
            }
            val previousDate = startDate.minusDays(1)
            val rows = query(
                DAILY_SQL,
                previousDate.atStartOfDay(),
                endDate.plusDays(1).atStartOfDay(),
                RowMapper { row, _ ->
                    DailyPostStats(row.getObject("post_date", LocalDate::class.java), row.getLong("post_count"))
                },
            )
            PostPeriodStatsResponse(
                startDate,
                endDate,
                rows.filter { row -> !row.date.isBefore(startDate) },
                rows.filter { row -> row.date == previousDate }.sumOf { row -> row.count },
                query(
                    BOARD_SQL,
                    startDate.atStartOfDay(),
                    endDate.plusDays(1).atStartOfDay(),
                    RowMapper { row, _ -> BoardTypeStats(row.getString("board_type"), row.getLong("post_count")) },
                ),
            )
        }!!

    private fun <T> query(sql: String, start: LocalDateTime, end: LocalDateTime, mapper: RowMapper<T>): List<T> =
        jdbc.query(
            sql,
            PreparedStatementSetter { statement ->
                val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                statement.setTimestamp(1, Timestamp.valueOf(start), utc)
                statement.setTimestamp(2, Timestamp.valueOf(end), utc)
            },
            mapper,
        )

    private companion object {
        private val TODAY_SQL = """
            SELECT COUNT(post_id) AS post_count FROM pawbridge_community.posts
            WHERE created_at >= ? AND created_at < ? AND deleted_at IS NULL
        """.trimIndent() + "\n"

        private val DAILY_SQL = """
            SELECT CAST(created_at + INTERVAL '9 hours' AS DATE) AS post_date, COUNT(post_id) AS post_count
            FROM pawbridge_community.posts
            WHERE created_at >= ? AND created_at < ? AND deleted_at IS NULL
            GROUP BY CAST(created_at + INTERVAL '9 hours' AS DATE)
            ORDER BY CAST(created_at + INTERVAL '9 hours' AS DATE)
        """.trimIndent() + "\n"

        private val BOARD_SQL = """
            SELECT board_type, COUNT(post_id) AS post_count FROM pawbridge_community.posts
            WHERE created_at >= ? AND created_at < ? AND deleted_at IS NULL
            GROUP BY board_type
        """.trimIndent() + "\n"

        private val SEOUL = ZoneId.of("Asia/Seoul")
    }
}
