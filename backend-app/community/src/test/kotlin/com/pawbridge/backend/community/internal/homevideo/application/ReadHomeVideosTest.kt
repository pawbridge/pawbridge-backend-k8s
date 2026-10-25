package com.pawbridge.backend.community.internal.homevideo.application

import io.mockk.every
import io.mockk.mockk
import io.mockk.verifySequence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

class ReadHomeVideosTest {
    @Test
    fun `시계가 계속 바뀔 때__읽으면__리더를 부르기 전에 정확히 29일을 한 번만 뺀다`() {
        // given
        val clock = mockk<Clock>()
        val reader = mockk<HomeVideoReader>()
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

        every { clock.instant() } returnsMany listOf(now, now.plusSeconds(1))
        every { reader.read(any()) } returns expected

        // when
        val result = ReadHomeVideos(reader, clock).read()

        // then
        assertEquals(expected, result)
        verifySequence {
            clock.instant()
            reader.read(cutoff)
        }
    }
}
