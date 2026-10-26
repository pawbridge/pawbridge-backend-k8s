package com.pawbridge.backend

import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.animal.internal.statistics.application.AnimalStatistics
import com.pawbridge.backend.boot.configuration.AnimalStatisticsConfiguration
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate

@WebMvcTest(
    properties = ["pawbridge.animal-statistics.enabled=true"],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [AnimalStatisticsConfiguration::class, ReadSecurityConfiguration::class, F3TestInputs.SecurityInputs::class],
)
class AnimalStatisticsHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockkBean
    lateinit var statistics: AnimalStatistics

    @Test
    fun `통계가 켜졌을 때__익명이나 잘못된 토큰이 공개 GET 3개를 부르면__세션 없이 등록된 컨트롤러에 닿는다`() {
        // given
        every { statistics.today() } returns AnimalStatistics.Today(LocalDate.of(2026, 10, 20), 2L, 3L)
        every { statistics.status(null, null) } returns listOf()
        every { statistics.regional(null, null) } returns listOf()

        // when
        val today = mvc.perform(get("/api/v1/animals/stats/today").header("Authorization", "Bearer invalid"))
        val statusList = mvc.perform(get("/api/v1/animals/stats/status"))
        val regional = mvc.perform(get("/api/v1/animals/stats/regional"))

        // then
        today.andExpect(status().isOk())
            .andExpect(jsonPath("$.rescuedToday").value(2))
            .andExpect(header().doesNotExist("Set-Cookie"))
        statusList.andExpect(status().isOk()).andExpect(content().json("[]"))
        regional.andExpect(status().isOk()).andExpect(content().json("[]"))
        verify(exactly = 1) { statistics.today() }
        verify(exactly = 1) { statistics.status(null, null) }
        verify(exactly = 1) { statistics.regional(null, null) }
    }

    @Test
    fun `관리자 GET 2개가 켜졌을 때__관리자가 기간으로 조회하면__원래 응답과 서비스 호출을 유지한다`() {
        // given
        val from = LocalDate.of(2026, 10, 1)
        val to = LocalDate.of(2026, 10, 20)
        val admin = "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")

        every { statistics.dailyAnimals(from, to) } returns listOf()
        every { statistics.intakeTrend(from, to) } returns AnimalStatistics.IntakeTrend(from, to, listOf(), 0)

        // when
        val daily = mvc.perform(
            get("/api/v1/admin/stats/daily-animals")
                .param("startDate", from.toString())
                .param("endDate", to.toString())
                .header("Authorization", admin),
        )
        val trend = mvc.perform(
            get("/api/v1/admin/stats/intake-trend")
                .param("startDate", from.toString())
                .param("endDate", to.toString())
                .header("Authorization", admin),
        )

        // then
        daily.andExpect(status().isOk())
            .andExpect(content().json("[]"))
            .andExpect(header().doesNotExist("Set-Cookie"))
        trend.andExpect(status().isOk())
            .andExpect(jsonPath("$.previousDayCount").value(0))
        verify(exactly = 1) { statistics.dailyAnimals(from, to) }
        verify(exactly = 1) { statistics.intakeTrend(from, to) }
    }

}
