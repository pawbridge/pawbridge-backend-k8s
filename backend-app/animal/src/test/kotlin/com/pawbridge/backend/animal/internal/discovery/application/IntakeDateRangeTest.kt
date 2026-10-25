package com.pawbridge.backend.animal.internal.discovery.application

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** Original pure validation test, with the package changed for the new owner. */
class IntakeDateRangeTest {
    private val date: LocalDate = LocalDate.of(2026, 9, 27)

    @Test
    fun accepts_inclusive_single_day_and_366_day_range() {
        assertThat(IntakeDateRange(date, date).from).isEqualTo(date)
        assertThat(IntakeDateRange(date.minusDays(365), date).to).isEqualTo(date)
    }

    @Test
    fun rejects_partial_inverted_and_excessive_ranges() {
        assertThatIllegalArgumentException().isThrownBy { IntakeDateRange(null, date) }
        assertThatIllegalArgumentException().isThrownBy { IntakeDateRange(date, null) }
        assertThatIllegalArgumentException().isThrownBy { IntakeDateRange(date, date.minusDays(1)) }
        assertThatIllegalArgumentException().isThrownBy { IntakeDateRange(date.minusDays(366), date) }
        assertThatIllegalArgumentException().isThrownBy {
            IntakeDateRange(LocalDate.of(0, 1, 1), LocalDate.of(0, 1, 2))
        }
    }
}
