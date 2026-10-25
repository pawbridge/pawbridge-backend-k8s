package com.pawbridge.backend

import com.pawbridge.backend.animal.internal.statistics.application.AnimalStatistics
import com.pawbridge.backend.boot.configuration.AnimalStatisticsConfiguration
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when` as whenever
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.HttpMethod
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.bean.override.mockito.MockitoBean
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

    @MockitoBean
    lateinit var statistics: AnimalStatistics

    @Test
    fun given__enabled_statistics__when__anonymous_or_bad_bearer_calls_three_exact_public_gets__then__reach_registered_controllers_without_session() {
        whenever(statistics.today()).thenReturn(AnimalStatistics.Today(LocalDate.of(2026, 10, 20), 2L, 3L))
        whenever(statistics.status(null, null)).thenReturn(listOf())
        whenever(statistics.regional(null, null)).thenReturn(listOf())
        mvc.perform(get("/api/v1/animals/stats/today").header("Authorization", "Bearer invalid"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.rescuedToday").value(2))
            .andExpect(header().doesNotExist("Set-Cookie"))
        mvc.perform(get("/api/v1/animals/stats/status")).andExpect(status().isOk()).andExpect(content().json("[]"))
        mvc.perform(get("/api/v1/animals/stats/regional")).andExpect(status().isOk()).andExpect(content().json("[]"))
        verify(statistics).today()
        verify(statistics).status(null, null)
        verify(statistics).regional(null, null)
    }

    @Test
    fun given__two_enabled_admin_gets__when__checking_missing_invalid_user_shelter_and_admin_tokens__then__keep_jwt_and_admin_role_before_mvc() {
        val from = LocalDate.of(2026, 10, 1)
        val to = LocalDate.of(2026, 10, 20)
        for (path in listOf("/api/v1/admin/stats/daily-animals", "/api/v1/admin/stats/intake-trend")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401))
            mvc.perform(get(path).header("Authorization", "Bearer invalid")).andExpect(status().isUnauthorized())
            for (role in listOf("ROLE_USER", "ROLE_SHELTER")) {
                mvc.perform(get(path).header("Authorization", "Bearer " + F3TestInputs.token(7, role)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value("관리자 권한이 필요합니다."))
            }
        }
        verifyNoInteractions(statistics)
        whenever(statistics.dailyAnimals(from, to)).thenReturn(listOf())
        whenever(statistics.intakeTrend(from, to)).thenReturn(AnimalStatistics.IntakeTrend(from, to, listOf(), 0))
        val admin = "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")
        mvc.perform(
            get("/api/v1/admin/stats/daily-animals")
                .param("startDate", from.toString())
                .param("endDate", to.toString())
                .header("Authorization", admin),
        )
            .andExpect(status().isOk())
            .andExpect(content().json("[]"))
            .andExpect(header().doesNotExist("Set-Cookie"))
        mvc.perform(
            get("/api/v1/admin/stats/intake-trend")
                .param("startDate", from.toString())
                .param("endDate", to.toString())
                .header("Authorization", admin),
        )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.previousDayCount").value(0))
        verify(statistics).dailyAnimals(from, to)
        verify(statistics).intakeTrend(from, to)
    }

    @Test
    fun given__exact_statistics_gets_only__when__requesting_neighbors_aliases_or_mutations_as_admin__then__deny_and_preserve_existing_private_user_rejection() {
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
        verifyNoInteractions(statistics)
    }
}
