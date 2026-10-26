package com.pawbridge.backend

import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.animal.internal.statistics.application.AnimalStatistics
import com.pawbridge.backend.boot.configuration.AnimalStatisticsConfiguration
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import io.mockk.confirmVerified
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class])
@ContextConfiguration(
    classes = [AnimalStatisticsConfiguration::class, ReadSecurityConfiguration::class, F3TestInputs.SecurityInputs::class],
)
class AnimalStatisticsDisabledHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockkBean
    lateinit var statistics: AnimalStatistics

    @Test
    fun `통계 플래그가 기본값일 때__익명이나 관리자가 GET 5개를 요청하면__컨트롤러 호출 없이 기본 거부를 유지한다`() {
        // given
        val paths = listOf(
            "/api/v1/animals/stats/today",
            "/api/v1/animals/stats/status",
            "/api/v1/animals/stats/regional",
            "/api/v1/admin/stats/daily-animals",
            "/api/v1/admin/stats/intake-trend",
        )

        // when
        for (path in paths) {
            mvc.perform(get(path)).andExpect(status().isForbidden())
            mvc.perform(get(path).header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")))
                .andExpect(status().isForbidden())
        }

        // then
        confirmVerified(statistics)
    }
}
