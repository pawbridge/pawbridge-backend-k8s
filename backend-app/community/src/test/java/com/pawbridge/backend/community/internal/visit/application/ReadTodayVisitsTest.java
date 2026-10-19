package com.pawbridge.backend.community.internal.visit.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ReadTodayVisitsTest {
    @ParameterizedTest
    @CsvSource({
            "2026-10-19T14:59:59.999999999Z, UTC, 2026-10-19",
            "2026-10-19T15:00:00Z, America/Los_Angeles, 2026-10-20"
    })
    void given__instant_at_korean_midnight_boundary_and_different_clock_zone__when__reading__then__use_korean_date(
            String instant, String clockZone, LocalDate expectedDay) {
        VisitCountReader reader = mock(VisitCountReader.class);
        when(reader.count(expectedDay)).thenReturn(41L);
        Clock clock = Clock.fixed(Instant.parse(instant), ZoneId.of(clockZone));

        assertEquals(new TodayVisits(expectedDay, 41L), new ReadTodayVisits(reader, clock).read());

        verify(reader).count(expectedDay);
        verifyNoMoreInteractions(reader);
    }

    @Test
    void given__query_waits_across_korean_midnight__when__reading__then__clock_is_read_once_before_query_and_original_day_is_returned() {
        var instant = new AtomicReference<>(Instant.parse("2026-10-19T14:59:59Z"));
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(invocation -> instant.get());
        VisitCountReader reader = mock(VisitCountReader.class);
        LocalDate day = LocalDate.of(2026, 10, 19);
        when(reader.count(day)).thenAnswer(invocation -> {
            instant.set(Instant.parse("2026-10-19T15:00:01Z"));
            return 77L;
        });

        assertEquals(new TodayVisits(day, 77L), new ReadTodayVisits(reader, clock).read());
        assertEquals(Instant.parse("2026-10-19T15:00:01Z"), instant.get());

        var order = org.mockito.Mockito.inOrder(clock, reader);
        order.verify(clock).instant();
        order.verify(reader).count(day);
        verifyNoMoreInteractions(clock, reader);
    }
}
