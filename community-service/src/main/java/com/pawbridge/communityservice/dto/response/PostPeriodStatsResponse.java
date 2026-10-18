package com.pawbridge.communityservice.dto.response;

import java.time.LocalDate;
import java.util.List;

public record PostPeriodStatsResponse(LocalDate startDate, LocalDate endDate,
        List<DailyPostStats> daily, long previousDayCount, List<BoardTypeStats> byBoardType) {}
