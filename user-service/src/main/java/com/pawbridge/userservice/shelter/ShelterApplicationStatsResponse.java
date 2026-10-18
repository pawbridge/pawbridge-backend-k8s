package com.pawbridge.userservice.shelter;

import java.time.LocalDate;
import java.util.List;

public record ShelterApplicationStatsResponse(LocalDate startDate, LocalDate endDate,
        List<DailyShelterApplicationStats> daily, long previousDayCount,
        long currentPending, long approvedCount, long rejectedCount) {}
