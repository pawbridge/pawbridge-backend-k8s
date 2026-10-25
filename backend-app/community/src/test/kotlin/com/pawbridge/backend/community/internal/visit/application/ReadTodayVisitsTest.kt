package com.pawbridge.backend.community.internal.visit.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when` as whenever
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicReference

class ReadTodayVisitsTest {
    @ParameterizedTest
    @CsvSource(
        "2026-10-19T14:59:59.999999999Z, UTC, 2026-10-19",
        "2026-10-19T15:00:00Z, America/Los_Angeles, 2026-10-20",
    )
    fun given__instant_at_korean_midnight_boundary_and_different_clock_zone__when__reading__then__use_korean_date(
        instant: String,
        clockZone: String,
        expectedDay: LocalDate,
    ) {
        val reader = mock(VisitCountReader::class.java)
        whenever(reader.count(expectedDay)).thenReturn(41L)
        val clock = Clock.fixed(Instant.parse(instant), ZoneId.of(clockZone))

        assertEquals(TodayVisits(expectedDay, 41L), ReadTodayVisits(reader, clock).read())

        verify(reader).count(expectedDay)
        verifyNoMoreInteractions(reader)
    }

    @Test
    fun given__query_waits_across_korean_midnight__when__reading__then__clock_is_read_once_before_query_and_original_day_is_returned() {
        val instant = AtomicReference(Instant.parse("2026-10-19T14:59:59Z"))
        val clock = mock(Clock::class.java)
        whenever(clock.instant()).thenAnswer { instant.get() }
        val reader = mock(VisitCountReader::class.java)
        val day = LocalDate.of(2026, 10, 19)
        whenever(reader.count(day)).thenAnswer {
            instant.set(Instant.parse("2026-10-19T15:00:01Z"))
            77L
        }

        assertEquals(TodayVisits(day, 77L), ReadTodayVisits(reader, clock).read())
        assertEquals(Instant.parse("2026-10-19T15:00:01Z"), instant.get())

        val order = inOrder(clock, reader)
        order.verify(clock).instant()
        order.verify(reader).count(day)
        verifyNoMoreInteractions(clock, reader)
    }
}
