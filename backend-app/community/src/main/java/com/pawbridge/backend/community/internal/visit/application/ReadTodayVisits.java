package com.pawbridge.backend.community.internal.visit.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

public final class ReadTodayVisits {
    private static final ZoneId VISIT_ZONE = ZoneId.of("Asia/Seoul");

    private final VisitCountReader reader;
    private final Clock clock;

    public ReadTodayVisits(VisitCountReader reader, Clock clock) {
        this.reader = reader;
        this.clock = clock;
    }

    public TodayVisits read() {
        LocalDate day = clock.instant().atZone(VISIT_ZONE).toLocalDate();
        return new TodayVisits(day, reader.count(day));
    }
}
