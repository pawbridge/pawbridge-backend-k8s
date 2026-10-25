package com.pawbridge.backend.community.internal.homevideo.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when` as whenever
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

class ReadHomeVideosTest {
    @Test
    fun given__changing_clock__when__reading__then__subtract_exact_twenty_nine_days_once_before_calling_reader() {
        val clock = mock(Clock::class.java)
        val reader = mock(HomeVideoReader::class.java)
        val now = Instant.parse("2026-10-19T15:00:00.123456789Z")
        val cutoff = now.minus(Duration.ofDays(29))
        val expected = listOf(
            HomeVideo(
                UUID.fromString("00000000-0000-0000-0000-000000000042"),
                "abcdefghijk",
                true,
                2,
                Instant.parse("2026-10-01T00:00:00.654321Z"),
                "",
                null,
                null,
                null,
                true,
                now,
            ),
        )
        whenever(clock.instant()).thenReturn(now, now.plusSeconds(1))
        whenever(reader.read(cutoff)).thenReturn(expected)

        assertEquals(expected, ReadHomeVideos(reader, clock).read())

        val calls = inOrder(clock, reader)
        calls.verify(clock).instant()
        calls.verify(reader).read(cutoff)
        verifyNoMoreInteractions(clock, reader)
    }
}
