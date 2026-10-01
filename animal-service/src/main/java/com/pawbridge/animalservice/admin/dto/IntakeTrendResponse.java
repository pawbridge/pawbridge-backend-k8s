package com.pawbridge.animalservice.admin.dto;

import java.time.LocalDate;
import java.util.List;

/** Current retained animal records grouped by APMS intake date, not first storage date. */
public record IntakeTrendResponse(LocalDate startDate, LocalDate endDate,
                                  List<DailyAnimalStatsResponse> daily, long previousDayCount) {}
