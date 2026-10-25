package com.pawbridge.backend.animal.internal.travel.infrastructure.collection

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class TourApiPropertiesTest {
    @Test
    fun givenDefaults__whenConfigured__thenUseBoundedCollectionAndFourteenDayRefresh() {
        val properties = TourApiProperties()

        assertThat(properties.maxPagesPerRun).isEqualTo(10)
        assertThat(properties.maxDetailsPerRun).isEqualTo(18)
        assertThat(properties.maxVisitDetailsPerRun).isEqualTo(18)
        assertThat(properties.dailyRequestLimit).isEqualTo(900)
        assertThat(properties.detailRefreshDays).isEqualTo(14)
        assertThat(properties.isBulkPetEnabled).isFalse()
        assertThat(properties.maxBulkPagesPerRun).isEqualTo(10)
        assertThat(properties.koreanServiceKey).isEmpty()
    }

    @Test
    fun givenNonpositiveRefreshDays__whenConfigured__thenReject() {
        for (days in intArrayOf(0, -1)) {
            assertThatThrownBy { TourApiProperties().detailRefreshDays = days }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun givenVisitDetailBatchOutsideBounds__whenConfigured__thenReject() {
        for (limit in intArrayOf(0, 101)) {
            assertThatThrownBy { TourApiProperties().maxVisitDetailsPerRun = limit }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun givenBudgetAboveCeiling__whenConfigured__thenReject() {
        for (limit in intArrayOf(0, 1001)) {
            assertThatThrownBy { TourApiProperties().dailyRequestLimit = limit }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}
