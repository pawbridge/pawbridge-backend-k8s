package com.pawbridge.backend.community.internal.post.application;

import java.time.LocalDate;
import java.util.List;

public interface AdminPostStatisticsQuery {
    long today();
    PostPeriodStatsResponse period(LocalDate startDate, LocalDate endDate);

    record DailyPostStats(LocalDate date, long count) { }
    record BoardTypeStats(String boardType, long count) { }
    record PostPeriodStatsResponse(LocalDate startDate, LocalDate endDate,
            List<DailyPostStats> daily, long previousDayCount, List<BoardTypeStats> byBoardType) { }
}
