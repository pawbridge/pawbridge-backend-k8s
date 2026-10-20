package com.pawbridge.backend.community.internal.visit.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public interface VisitCollectionStore {
    void start(Instant instant);
    Instant startedAt();
    void lockDay(LocalDate day, boolean exclusive);
    void count(LocalDate day, String hash);
    long today(LocalDate day);
    List<VisitCollection.Day> period(LocalDate start, LocalDate end);
    List<LocalDate> expiredDays(LocalDate today);
    void deleteDay(LocalDate day);
}
