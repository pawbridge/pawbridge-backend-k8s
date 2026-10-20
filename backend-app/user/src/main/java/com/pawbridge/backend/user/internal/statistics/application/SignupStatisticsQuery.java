package com.pawbridge.backend.user.internal.statistics.application;

import java.time.LocalDate;
import java.util.List;

public interface SignupStatisticsQuery {
    List<DailySignupStats> daily(LocalDate startDate, LocalDate endDate);

    SignupPeriods periods();

    record DailySignupStats(LocalDate date, Long count) {
    }

    record SignupPeriods(List<DailySignupStats> today, List<DailySignupStats> last7Days,
                         List<DailySignupStats> last30Days, List<DailySignupStats> thisMonth) {
    }
}
