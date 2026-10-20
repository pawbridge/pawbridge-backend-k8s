package com.pawbridge.backend.community.internal.visit.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public interface VisitCollection {
    void start();
    TodayVisits count(String cookie);
    Period period(LocalDate start, LocalDate end);
    void cleanup();

    public record Day(LocalDate date, long count) {}
    public record Period(LocalDate startDate, LocalDate endDate, LocalDate today,
            Instant collectionStartedAt, List<Day> daily) {}
}
