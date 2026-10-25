package com.pawbridge.backend.community.internal.visit.application

import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifySequence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
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
    fun `한국 자정 경계의 시각과 다른 시계 시간대가 주어졌을 때__읽으면__한국 날짜를 쓴다`(
        instant: String,
        clockZone: String,
        expectedDay: LocalDate,
    ) {
        // given
        val reader = mockk<VisitCountReader>()
        val clock = Clock.fixed(Instant.parse(instant), ZoneId.of(clockZone))

        every { reader.count(any()) } returns 41L

        // when
        val result = ReadTodayVisits(reader, clock).read()

        // then
        assertEquals(TodayVisits(expectedDay, 41L), result)
        verify(exactly = 1) { reader.count(expectedDay) }
        confirmVerified(reader)
    }

    @Test
    fun `조회가 한국 자정을 넘겨 기다릴 때__읽으면__질의 전에 시계를 한 번만 읽고 원래 날짜를 돌려준다`() {
        // given
        val instant = AtomicReference(Instant.parse("2026-10-19T14:59:59Z"))
        val clock = mockk<Clock>()
        val reader = mockk<VisitCountReader>()
        val day = LocalDate.of(2026, 10, 19)

        every { clock.instant() } answers { instant.get() }
        every { reader.count(any()) } answers {
            instant.set(Instant.parse("2026-10-19T15:00:01Z"))
            77L
        }

        // when
        val result = ReadTodayVisits(reader, clock).read()

        // then
        assertEquals(TodayVisits(day, 77L), result)
        assertEquals(Instant.parse("2026-10-19T15:00:01Z"), instant.get())
        verifySequence {
            clock.instant()
            reader.count(day)
        }
    }
}
