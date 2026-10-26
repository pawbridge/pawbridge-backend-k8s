package com.pawbridge.backend.animal.internal.travel.infrastructure.collection

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test

class TourApiPropertiesTest {
    @Test
    fun `기본 설정일 때__구성하면__제한된 수집과 14일 갱신 주기를 쓴다`() {
        // given

        // when
        val properties = TourApiProperties()

        // then
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
    fun `갱신 일수가 양수가 아닐 때__구성하면__거부한다`() {
        // given
        val invalidDays = intArrayOf(0, -1)

        for (days in invalidDays) {
            // when
            val failure = catchThrowable { TourApiProperties().detailRefreshDays = days }

            // then
            assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `방문 상세 배치가 범위를 벗어날 때__구성하면__거부한다`() {
        // given
        val invalidLimits = intArrayOf(0, 101)

        for (limit in invalidLimits) {
            // when
            val failure = catchThrowable { TourApiProperties().maxVisitDetailsPerRun = limit }

            // then
            assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `예산이 상한을 벗어날 때__구성하면__거부한다`() {
        // given
        val invalidLimits = intArrayOf(0, 1001)

        for (limit in invalidLimits) {
            // when
            val failure = catchThrowable { TourApiProperties().dailyRequestLimit = limit }

            // then
            assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}
