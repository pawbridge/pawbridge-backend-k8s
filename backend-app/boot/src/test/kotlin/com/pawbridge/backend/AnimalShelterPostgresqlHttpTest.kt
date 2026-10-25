package com.pawbridge.backend

import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads
import com.pawbridge.backend.boot.configuration.AnimalShelterConfiguration
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate

@WebMvcTest(
    properties = ["pawbridge.animal-shelters.enabled=true", "pawbridge.animal-query.backend=postgresql"],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [AnimalShelterConfiguration::class, ReadSecurityConfiguration::class, F3TestInputs.SecurityInputs::class],
)
class AnimalShelterPostgresqlHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockitoBean
    lateinit var shelters: ShelterReads

    @Test
    fun given__explicit_postgresql_and_shelters_enabled__when__requesting_exact_discovery_gets__then__reach_source_selected_controller_without_jwt() {
        whenever(shelters.discover(anyString(), anyString(), any<LocalDate>(), any<LocalDate>(), anyInt(), anyInt()))
            .thenReturn(ShelterReads.Page(listOf(), 0))
        whenever(shelters.observations(eq(42L), any<LocalDate>(), any<LocalDate>())).thenReturn(listOf())
        mvc.perform(
            get("/api/v1/shelters/discovery")
                .param("intakeFrom", "2026-10-01")
                .param("intakeTo", "2026-10-20")
                .header("Authorization", "Bearer invalid"),
        )
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist("Set-Cookie"))
        mvc.perform(get("/api/v1/shelters/42/observations").param("from", "2026-10-01").param("to", "2026-10-20"))
            .andExpect(status().isOk())
            .andExpect(content().json("[]"))
        verify(shelters).discover("", "", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 20), 0, 12)
        verify(shelters).observations(42, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 20))
    }

    @Test
    fun given__postgresql_routes_enabled__when__requesting_adjacent_paths_or_writes__then__deny_without_broadening_nested_access() {
        val admin = "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")
        val paths = listOf(
            "/api/v1/shelters/discovery/extra",
            "/api/v1/shelters/42/observations/extra",
            "/api/v1/shelters/abc/observations",
            "/api/v1/shelters/-1/observations",
            "/api/shelters/discovery",
        )
        for (path in paths) {
            mvc.perform(get(path).header("Authorization", admin)).andExpect(status().isForbidden())
        }
        for (method in listOf(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)) {
            for (path in listOf("/api/v1/shelters/discovery", "/api/v1/shelters/42/observations")) {
                mvc.perform(request(method, path).header("Authorization", admin)).andExpect(status().isForbidden())
            }
        }
        verifyNoInteractions(shelters)
    }
}
