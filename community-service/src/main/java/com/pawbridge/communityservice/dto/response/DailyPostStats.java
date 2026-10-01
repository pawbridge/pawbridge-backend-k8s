package com.pawbridge.communityservice.dto.response;

import java.time.LocalDate;

public record DailyPostStats(LocalDate date, long count) {}
