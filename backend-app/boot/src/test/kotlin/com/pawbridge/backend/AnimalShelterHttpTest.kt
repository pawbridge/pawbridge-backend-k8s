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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(
    properties = ["pawbridge.animal-shelters.enabled=true"],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [AnimalShelterConfiguration::class, ReadSecurityConfiguration::class, F3TestInputs.SecurityInputs::class],
)
class AnimalShelterHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockkBean
    lateinit var shelters: ShelterReads

    @Test
    fun `보호소가 켜졌고 postgresql 지정이 없을 때__표준 GET 4개를 요청하면__JWT나 세션 없이 일반 조회에만 닿는다`() {
        // given
        val detail = ShelterReads.Details(42L, "CARE-42", "fixture", null, null, null, null, null, null, null, null, null, null, null)

        every { shelters.search(any(), any()) } returns ShelterReads.Page(listOf(), 0)
        every { shelters.findById(42) } returns detail
        every { shelters.findByCareRegNo("CARE-42") } returns detail
        every { shelters.count() } returns 17L

        // when
        val list = mvc.perform(get("/api/v1/shelters").param("keyword", "fixture").header("Authorization", "Bearer invalid"))
        val byId = mvc.perform(get("/api/v1/shelters/42").param("view", "detail"))
        val byCareRegNo = mvc.perform(get("/api/v1/shelters/by-care-reg-no/CARE-42"))
        val count = mvc.perform(get("/api/v1/shelters/count"))

        // then
        list.andExpect(status().isOk())
            .andExpect(header().doesNotExist("Set-Cookie"))
        byId.andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(42))
        byCareRegNo.andExpect(status().isOk())
            .andExpect(jsonPath("$.careRegNo").value("CARE-42"))
        count.andExpect(status().isOk()).andExpect(content().string("17"))
        verify(exactly = 1) { shelters.search(any(), any()) }
        verify(exactly = 1) { shelters.findById(42) }
        verify(exactly = 1) { shelters.findByCareRegNo("CARE-42") }
        verify(exactly = 1) { shelters.count() }
    }

}
