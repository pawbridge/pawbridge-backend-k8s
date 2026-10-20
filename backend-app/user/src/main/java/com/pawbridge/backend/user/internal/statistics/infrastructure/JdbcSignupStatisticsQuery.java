package com.pawbridge.backend.user.internal.statistics.infrastructure;

import com.pawbridge.backend.user.internal.statistics.application.SignupStatisticsQuery;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.transaction.support.TransactionOperations;

public final class JdbcSignupStatisticsQuery implements SignupStatisticsQuery {
    private static final String DAILY_SQL = """
            SELECT CAST(created_at + INTERVAL '9 hours' AS DATE) AS signup_date, count(*) AS signup_count
            FROM pawbridge_user.users
            WHERE CAST(created_at + INTERVAL '9 hours' AS DATE) BETWEEN ? AND ?
            GROUP BY CAST(created_at + INTERVAL '9 hours' AS DATE)
            ORDER BY CAST(created_at + INTERVAL '9 hours' AS DATE)
            """;
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final JdbcOperations jdbc;
    private final TransactionOperations readOnlyTransaction;
    private final Clock clock;

    public JdbcSignupStatisticsQuery(JdbcOperations jdbc, TransactionOperations readOnlyTransaction, Clock clock) {
        this.jdbc = jdbc;
        this.readOnlyTransaction = readOnlyTransaction;
        this.clock = clock;
    }

    @Override
    public List<DailySignupStats> daily(LocalDate startDate, LocalDate endDate) {
        return readOnlyTransaction.execute(status -> query(startDate, endDate));
    }

    @Override
    public SignupPeriods periods() {
        return readOnlyTransaction.execute(status -> {
            LocalDate today = LocalDate.now(clock.withZone(SEOUL));
            LocalDate last7DaysStart = today.minusDays(6);
            LocalDate last30DaysStart = today.minusDays(29);
            LocalDate thisMonthStart = today.withDayOfMonth(1);
            var todayRaw = query(today, today);
            var last7DaysRaw = query(last7DaysStart, today);
            var last30DaysRaw = query(last30DaysStart, today);
            var thisMonthRaw = query(thisMonthStart, today);
            return new SignupPeriods(fill(today, today, todayRaw),
                    fill(last7DaysStart, today, last7DaysRaw),
                    fill(last30DaysStart, today, last30DaysRaw),
                    fill(thisMonthStart, today, thisMonthRaw));
        });
    }

    private List<DailySignupStats> query(LocalDate startDate, LocalDate endDate) {
        return jdbc.query(DAILY_SQL, (rows, index) -> new DailySignupStats(
                rows.getObject("signup_date", LocalDate.class), rows.getLong("signup_count")), startDate, endDate);
    }

    private static List<DailySignupStats> fill(LocalDate startDate, LocalDate endDate, List<DailySignupStats> rows) {
        var counts = rows.stream().collect(Collectors.toMap(DailySignupStats::date, DailySignupStats::count));
        List<DailySignupStats> result = new ArrayList<>();
        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            result.add(new DailySignupStats(date, counts.getOrDefault(date, 0L)));
        }
        return result;
    }
}
