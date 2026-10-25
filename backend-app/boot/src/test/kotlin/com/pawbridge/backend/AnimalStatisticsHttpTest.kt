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
import org.springframework.http.HttpMethod
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
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
    fun `관리자 GET 2개가 켜졌을 때__토큰 없음 잘못됨 사용자 보호소 관리자를 확인하면__MVC 전에 JWT와 관리자 역할을 지킨다`() {
        // given
        val from = LocalDate.of(2026, 10, 1)
        val to = LocalDate.of(2026, 10, 20)
        val admin = "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")

        every { statistics.dailyAnimals(from, to) } returns listOf()
        every { statistics.intakeTrend(from, to) } returns AnimalStatistics.IntakeTrend(from, to, listOf(), 0)

        // when
        for (path in listOf("/api/v1/admin/stats/daily-animals", "/api/v1/admin/stats/intake-trend")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401))
            mvc.perform(get(path).header("Authorization", "Bearer invalid")).andExpect(status().isUnauthorized())
            for (role in listOf("ROLE_USER", "ROLE_SHELTER")) {
                mvc.perform(get(path).header("Authorization", "Bearer " + F3TestInputs.token(7, role)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value("관리자 권한이 필요합니다."))
            }
        }
        confirmVerified(statistics)
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

    @Test
    fun `정확한 통계 GET만 열렸을 때__관리자가 이웃 경로 별칭 변경을 요청하면__거부하고 기존 비공개 사용자 거부를 유지한다`() {
        // given
        val admin = "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")
        val paths = listOf(
            "/api/v1/animals/stats",
            "/api/v1/animals/stats/today/extra",
            "/api/v1/animals/stats/unknown",
            "/api/v1/admin/stats",
            "/api/v1/admin/stats/daily-animals/extra",
            "/api/v1/admin/stats/unknown",
            // Security chain only: LegacyApiPathFilter (the temporary /api -> /api/v1 rewrite) is a servlet filter outside it, so the old alias stays denied here; the rewrite is covered by GatewayFeaturesHttpTest.
            "/api/animals/stats/today",
            "/api/admin/stats/daily-animals",
            "/api/admin/stats/intake-trend",
        )

        // when
        for (path in paths) {
            mvc.perform(get(path).header("Authorization", admin)).andExpect(status().isForbidden())
        }
        for (method in listOf(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)) {
            for (path in listOf("/api/v1/animals/stats/today", "/api/v1/admin/stats/daily-animals")) {
                mvc.perform(request(method, path).header("Authorization", admin)).andExpect(status().isForbidden())
            }
        }
        mvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized())
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer invalid")).andExpect(status().isUnauthorized())

        // then
        confirmVerified(statistics)
    }
}
