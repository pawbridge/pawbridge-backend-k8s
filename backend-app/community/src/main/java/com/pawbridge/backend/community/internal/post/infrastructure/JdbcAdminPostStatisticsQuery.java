package com.pawbridge.backend.community.internal.post.infrastructure;

import com.pawbridge.backend.community.internal.post.application.AdminPostStatisticsQuery;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.server.ResponseStatusException;

public final class JdbcAdminPostStatisticsQuery implements AdminPostStatisticsQuery {
    private static final String TODAY_SQL = """
            SELECT COUNT(post_id) AS post_count FROM pawbridge_community.posts
            WHERE created_at >= ? AND created_at < ? AND deleted_at IS NULL
            """;
    private static final String DAILY_SQL = """
            SELECT CAST(created_at + INTERVAL '9 hours' AS DATE) AS post_date, COUNT(post_id) AS post_count
            FROM pawbridge_community.posts
            WHERE created_at >= ? AND created_at < ? AND deleted_at IS NULL
            GROUP BY CAST(created_at + INTERVAL '9 hours' AS DATE)
            ORDER BY CAST(created_at + INTERVAL '9 hours' AS DATE)
            """;
    private static final String BOARD_SQL = """
            SELECT board_type, COUNT(post_id) AS post_count FROM pawbridge_community.posts
            WHERE created_at >= ? AND created_at < ? AND deleted_at IS NULL
            GROUP BY board_type
            """;
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final JdbcOperations jdbc;
    private final TransactionOperations todayTransaction;
    private final TransactionOperations periodTransaction;
    private final Clock clock;

    public JdbcAdminPostStatisticsQuery(JdbcOperations jdbc, TransactionOperations todayTransaction,
            TransactionOperations periodTransaction, Clock clock) {
        this.jdbc = jdbc;
        this.todayTransaction = todayTransaction;
        this.periodTransaction = periodTransaction;
        this.clock = clock;
    }

    @Override
    public long today() {
        return todayTransaction.execute(status -> {
            LocalDateTime start = LocalDate.now(clock.withZone(ZoneId.systemDefault())).atStartOfDay();
            return query(TODAY_SQL, start, start.plusDays(1), (rows, index) -> rows.getLong("post_count")).getFirst();
        });
    }

    @Override
    public PostPeriodStatsResponse period(LocalDate startDate, LocalDate endDate) {
        return periodTransaction.execute(status -> {
            if (startDate == null || endDate == null || startDate.isAfter(endDate)
                    || endDate.isAfter(LocalDate.now(clock.withZone(SEOUL)))
                    || ChronoUnit.DAYS.between(startDate, endDate) >= 366) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "오늘까지 최대 366일의 올바른 조회 기간이 필요합니다.");
            }
            LocalDate previousDate = startDate.minusDays(1);
            var rows = query(DAILY_SQL, previousDate.atStartOfDay(), endDate.plusDays(1).atStartOfDay(),
                    (row, index) -> new DailyPostStats(row.getObject("post_date", LocalDate.class), row.getLong("post_count")));
            return new PostPeriodStatsResponse(startDate, endDate,
                    rows.stream().filter(row -> !row.date().isBefore(startDate)).toList(),
                    rows.stream().filter(row -> row.date().equals(previousDate)).mapToLong(DailyPostStats::count).sum(),
                    query(BOARD_SQL, startDate.atStartOfDay(), endDate.plusDays(1).atStartOfDay(),
                            (row, index) -> new BoardTypeStats(row.getString("board_type"), row.getLong("post_count"))));
        });
    }

    private <T> List<T> query(String sql, LocalDateTime start, LocalDateTime end, RowMapper<T> mapper) {
        return jdbc.query(sql, statement -> {
            Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            statement.setTimestamp(1, Timestamp.valueOf(start), utc);
            statement.setTimestamp(2, Timestamp.valueOf(end), utc);
        }, mapper);
    }
}
