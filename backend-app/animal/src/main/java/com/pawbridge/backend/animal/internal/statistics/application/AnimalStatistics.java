package com.pawbridge.backend.animal.internal.statistics.application;

import java.time.LocalDate;
import java.util.List;

/** Selected public/admin reads; counts describe retained records rather than lifecycle events. */
public interface AnimalStatistics {
    Today today();
    List<StatusCount> status(LocalDate startDate, LocalDate endDate);
    List<RegionalCount> regional(LocalDate startDate, LocalDate endDate);
    List<DailyCount> dailyAnimals(LocalDate startDate, LocalDate endDate);
    IntakeTrend intakeTrend(LocalDate startDate, LocalDate endDate);

    record Today(LocalDate date, Long rescuedToday, Long adoptedToday) { }
    record StatusCount(Status status, Long count) {
        public String label() { return status == null ? null : status.label(); }
    }
    record RegionalCount(String region, Long count) { }
    record DailyCount(LocalDate date, Long count) { }
    record IntakeTrend(LocalDate startDate, LocalDate endDate, List<DailyCount> daily, long previousDayCount) { }

    /** Original AnimalStatus names/descriptions only; write-side APMS parsing is not imported. */
    enum Status {
        NOTICE("공고중"), PROTECT("보호중"), ADOPTION_PENDING("입양대기중"), ADOPTED("종료(입양)"),
        EUTHANIZED("종료(안락사)"), NATURAL_DEATH("종료(자연사)"), RETURNED("종료(반환)"),
        DONATED("종료(기증)"), RELEASED("종료(방사)"), ESCAPED("탈출"), UNKNOWN("미상");
        private final String label;
        Status(String label) { this.label = label; }
        public String label() { return label; }
    }
}
