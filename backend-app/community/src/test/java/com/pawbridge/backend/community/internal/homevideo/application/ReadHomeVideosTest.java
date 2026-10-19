package com.pawbridge.backend.community.internal.homevideo.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReadHomeVideosTest {
    @Test
    void given__changing_clock__when__reading__then__subtract_exact_twenty_nine_days_once_before_calling_reader() {
        Clock clock = mock(Clock.class);
        HomeVideoReader reader = mock(HomeVideoReader.class);
        Instant now = Instant.parse("2026-10-19T15:00:00.123456789Z");
        Instant cutoff = now.minus(Duration.ofDays(29));
        List<HomeVideo> expected = List.of(new HomeVideo(
                UUID.fromString("00000000-0000-0000-0000-000000000042"),
                "abcdefghijk", true, 2, Instant.parse("2026-10-01T00:00:00.654321Z"),
                "", null, null, null, true, now));
        when(clock.instant()).thenReturn(now, now.plusSeconds(1));
        when(reader.read(cutoff)).thenReturn(expected);

        assertEquals(expected, new ReadHomeVideos(reader, clock).read());

        var calls = inOrder(clock, reader);
        calls.verify(clock).instant();
        calls.verify(reader).read(cutoff);
        verifyNoMoreInteractions(clock, reader);
    }
}
