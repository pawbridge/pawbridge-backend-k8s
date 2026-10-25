package com.pawbridge.backend

import com.pawbridge.backend.animal.internal.statistics.application.AnimalStatistics
import com.pawbridge.backend.boot.configuration.AnimalStatisticsConfiguration
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import org.junit.jupiter.api.Test
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.bean.override.mockito.MockitoBean
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

    @MockitoBean
    lateinit var statistics: AnimalStatistics

    @Test
    fun given__default_statistics_flag__when__requesting_all_five_gets_as_anonymous_or_admin__then__keep_default_deny_without_controller_calls() {
        val paths = listOf(
            "/api/v1/animals/stats/today",
            "/api/v1/animals/stats/status",
            "/api/v1/animals/stats/regional",
            "/api/v1/admin/stats/daily-animals",
            "/api/v1/admin/stats/intake-trend",
        )
        for (path in paths) {
            mvc.perform(get(path)).andExpect(status().isForbidden())
            mvc.perform(get(path).header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")))
                .andExpect(status().isForbidden())
        }
        verifyNoInteractions(statistics)
    }
}
