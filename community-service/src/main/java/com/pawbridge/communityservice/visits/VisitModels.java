package com.pawbridge.communityservice.visits;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class VisitModels {
    private VisitModels() {}
    public record Today(LocalDate date, long count) {}
    public record Day(LocalDate date, long count) {}
    public record Period(LocalDate startDate, LocalDate endDate, LocalDate today,
                         Instant collectionStartedAt, List<Day> daily) {}
}
