package com.pawbridge.backend.community.internal.visit.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.transaction.support.TransactionOperations;

public class VisitCollection {
    private final VisitCollectionStore repository;
    private final VisitIdentity cookies;
    private final Clock clock;
    private final TransactionOperations transactions;

    public VisitCollection(VisitCollectionStore repository, VisitIdentity cookies, Clock clock, TransactionOperations transactions) {
        this.repository = repository; this.cookies = cookies; this.clock = clock;
        this.transactions = transactions;
    }

    public void start() { repository.start(clock.instant()); }

    public TodayVisits count(String cookie) {
        LocalDate day = cookies.day(clock.instant());
        String identity = cookies.identity(cookie, day);
        if (identity == null) throw expired();
        return transactions.execute(status -> {
            repository.lockDay(day, false);
            // Reject a request that waited across midnight, including a late cleanup race.
            if (!day.equals(cookies.day(clock.instant()))) throw expired();
            repository.count(day, cookies.hash(day, identity));
            return new TodayVisits(day, repository.today(day));
        });
    }

    public Period period(LocalDate start, LocalDate end) {
        LocalDate today = cookies.day(clock.instant());
        if (start == null || end == null || end.isBefore(start) || end.isAfter(today)
                || ChronoUnit.DAYS.between(start,end) >= 366)
            throw new VisitCollectionRejectedException(VisitCollectionRejectedException.Reason.RANGE);
        Instant began = repository.startedAt();
        LocalDate first = began.atZone(VisitIdentity.ZONE).toLocalDate();
        Map<LocalDate,Long> counts = new HashMap<>();
        repository.period(start,end).forEach(row -> counts.put(row.date(),row.count()));
        List<Day> days = new ArrayList<>();
        for (LocalDate date = start.isBefore(first) ? first : start; !date.isAfter(end); date = date.plusDays(1))
            days.add(new Day(date, counts.getOrDefault(date,0L)));
        return new Period(start,end,today,began,List.copyOf(days));
    }

    public void cleanup() {
        var now = clock.instant().atZone(VisitIdentity.ZONE);
        if (now.toLocalTime().isBefore(LocalTime.of(0,5))) return;
        for (LocalDate date : repository.expiredDays(now.toLocalDate())) {
            transactions.executeWithoutResult(status -> {
                repository.lockDay(date,true);
                repository.deleteDay(date);
            });
        }
    }

    private VisitCollectionRejectedException expired() {
        return new VisitCollectionRejectedException(VisitCollectionRejectedException.Reason.COOKIE);
    }
    public record Day(LocalDate date, long count) {}
    public record Period(LocalDate startDate, LocalDate endDate, LocalDate today,
            Instant collectionStartedAt, List<Day> daily) {}
}
