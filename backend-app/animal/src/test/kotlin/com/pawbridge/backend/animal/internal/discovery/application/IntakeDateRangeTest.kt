package com.pawbridge.backend.animal.internal.discovery.application

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** Original pure validation test, with the package changed for the new owner. */
class IntakeDateRangeTest {
    private val date: LocalDate = LocalDate.of(2026, 9, 27)

    @Test
    fun `하루짜리 범위와 366일 범위일 때__입수일 범위를 만들면__양끝을 포함해 허용한다`() {
        // given
        val singleDay = date
        val longestStart = date.minusDays(365)

        // when
        val single = IntakeDateRange(singleDay, singleDay)
        val longest = IntakeDateRange(longestStart, date)

        // then
        assertThat(single.from).isEqualTo(date)
        assertThat(longest.to).isEqualTo(date)
    }

    @Test
    fun `한쪽만 있거나 뒤집혔거나 너무 긴 범위일 때__입수일 범위를 만들면__거부한다`() {
        // given
        val lowestYear = LocalDate.of(0, 1, 1)

        // when
        val withoutFrom = assertThatThrownBy { IntakeDateRange(null, date) }
        val withoutTo = assertThatThrownBy { IntakeDateRange(date, null) }
        val inverted = assertThatThrownBy { IntakeDateRange(date, date.minusDays(1)) }
        val tooLong = assertThatThrownBy { IntakeDateRange(date.minusDays(366), date) }
        val yearZero = assertThatThrownBy { IntakeDateRange(lowestYear, lowestYear.plusDays(1)) }

        // then
        withoutFrom.isInstanceOf(IllegalArgumentException::class.java)
        withoutTo.isInstanceOf(IllegalArgumentException::class.java)
        inverted.isInstanceOf(IllegalArgumentException::class.java)
        tooLong.isInstanceOf(IllegalArgumentException::class.java)
        yearZero.isInstanceOf(IllegalArgumentException::class.java)
    }
}
