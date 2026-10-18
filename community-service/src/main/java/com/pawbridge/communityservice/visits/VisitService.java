package com.pawbridge.communityservice.visits;

import static com.pawbridge.communityservice.visits.VisitModels.*;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

public class VisitService {
    private final VisitRepository repository;
    private final VisitCookie cookies;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public VisitService(VisitRepository repository, VisitCookie cookies, Clock clock, PlatformTransactionManager manager) {
        this.repository = repository; this.cookies = cookies; this.clock = clock;
        transactions = new TransactionTemplate(manager);
        transactions.setTimeout(10);
    }

    public void start() { repository.start(clock.instant()); }

    public Today count(String cookie) {
        LocalDate day = cookies.day(clock.instant());
        String identity = cookies.identity(cookie, day);
        if (identity == null) throw expired();
        return transactions.execute(status -> {
            repository.lockDay(day, false);
            // Reject a request that waited across midnight, including a late cleanup race.
            if (!day.equals(cookies.day(clock.instant()))) throw expired();
            repository.count(day, cookies.hash(day, identity));
            return new Today(day, repository.today(day));
        });
    }

    public Today today() {
        LocalDate day = cookies.day(clock.instant());
        return new Today(day, repository.today(day));
    }

    public Period period(LocalDate start, LocalDate end) {
        LocalDate today = cookies.day(clock.instant());
        if (start == null || end == null || end.isBefore(start) || end.isAfter(today)
                || ChronoUnit.DAYS.between(start,end) >= 366)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "오늘까지 최대 366일을 조회할 수 있습니다.");
        Instant began = repository.startedAt();
        LocalDate first = began.atZone(VisitCookie.ZONE).toLocalDate();
        Map<LocalDate,Long> counts = new HashMap<>();
        repository.period(start,end).forEach(row -> counts.put(row.date(),row.count()));
        List<Day> days = new ArrayList<>();
        for (LocalDate date = start.isBefore(first) ? first : start; !date.isAfter(end); date = date.plusDays(1))
            days.add(new Day(date, counts.getOrDefault(date,0L)));
        return new Period(start,end,today,began,List.copyOf(days));
    }

    public void cleanup() {
        var now = clock.instant().atZone(VisitCookie.ZONE);
        if (now.toLocalTime().isBefore(LocalTime.of(0,5))) return;
        for (LocalDate date : repository.expiredDays(now.toLocalDate())) {
            transactions.executeWithoutResult(status -> {
                repository.lockDay(date,true);
                repository.deleteDay(date);
            });
        }
    }

    private ResponseStatusException expired() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "방문 쿠키 초기화가 필요합니다.");
    }
}
