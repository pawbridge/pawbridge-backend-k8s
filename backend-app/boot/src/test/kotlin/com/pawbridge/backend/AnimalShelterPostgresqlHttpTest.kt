package com.pawbridge.backend

import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads
import com.pawbridge.backend.boot.configuration.AnimalShelterConfiguration
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
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

    @MockkBean
    lateinit var shelters: ShelterReads

    @Test
    fun `postgresql이 지정되고 보호소가 켜졌을 때__정확한 탐색 GET을 요청하면__JWT 없이 선택된 컨트롤러에 닿는다`() {
        // given
        every { shelters.discover(any(), any(), any(), any(), any(), any()) } returns ShelterReads.Page(listOf(), 0)
        every { shelters.observations(42L, any(), any()) } returns listOf()

        // when
        val discovery = mvc.perform(
            get("/api/v1/shelters/discovery")
                .param("intakeFrom", "2026-10-01")
                .param("intakeTo", "2026-10-20")
                .header("Authorization", "Bearer invalid"),
        )
        val observations = mvc.perform(
            get("/api/v1/shelters/42/observations").param("from", "2026-10-01").param("to", "2026-10-20"),
        )

        // then
        discovery.andExpect(status().isOk())
            .andExpect(header().doesNotExist("Set-Cookie"))
        observations.andExpect(status().isOk())
            .andExpect(content().json("[]"))
        verify(exactly = 1) { shelters.discover("", "", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 20), 0, 12) }
        verify(exactly = 1) { shelters.observations(42, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 20)) }
    }

}
