package com.pawbridge.backend

import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads
import com.pawbridge.backend.boot.configuration.AnimalShelterConfiguration
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

@WebMvcTest(
    properties = ["pawbridge.animal-query.backend=postgresql"],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [AnimalShelterConfiguration::class, ReadSecurityConfiguration::class, F3TestInputs.SecurityInputs::class],
)
class AnimalShelterDisabledHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockkBean
    lateinit var shelters: ShelterReads

    @Test
    fun `보호소가 기본 꺼짐이고 postgresql일 때__익명이나 관리자가 GET 6개를 요청하면__기본 거부를 유지한다`() {
        // given
        val paths = listOf(
            "/api/v1/shelters",
            "/api/v1/shelters/42",
            "/api/v1/shelters/by-care-reg-no/CARE-42",
            "/api/v1/shelters/count",
            "/api/v1/shelters/discovery",
            "/api/v1/shelters/42/observations",
        )

        // when
        for (path in paths) {
            mvc.perform(get(path)).andExpect(status().isForbidden())
            mvc.perform(get(path).header("Authorization", "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")))
                .andExpect(status().isForbidden())
        }

        // then
        confirmVerified(shelters)
    }
}
