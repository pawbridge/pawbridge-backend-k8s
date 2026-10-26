package com.pawbridge.backend

import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads
import com.pawbridge.backend.boot.configuration.AnimalShelterConfiguration
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

    @Test
    fun `일반 조회만 열렸을 때__관리자도 탐색 이웃 경로나 변경을 요청하면__보호소 코드 전에 거부한다`() {
        // given
        val admin = "Bearer " + F3TestInputs.token(7, "ROLE_ADMIN")
        val paths = listOf(
            "/api/v1/shelters/discovery",
            "/api/v1/shelters/42/observations",
            "/api/v1/shelters/abc",
            "/api/v1/shelters/-1",
            "/api/v1/shelters/42/extra",
            // Security chain only: LegacyApiPathFilter (the temporary /api -> /api/v1 rewrite) is a servlet filter outside it, so the old alias stays denied here; the rewrite is covered by GatewayFeaturesHttpTest.
            "/api/v1/shelters/by-care-reg-no/CARE-42/extra",
            "/api/v1/shelters/count/extra",
            "/api/shelters/42",
        )

        // when
        for (path in paths) {
            mvc.perform(get(path).header("Authorization", admin)).andExpect(status().isForbidden())
        }
        for (method in listOf(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)) {
            mvc.perform(request(method, "/api/v1/shelters/42").header("Authorization", admin))
                .andExpect(status().isForbidden())
        }

        // then
        confirmVerified(shelters)
    }

    @Test
    fun `기존 사용자 비공개 조회일 때__JWT가 없거나 잘못되면__기존 인증 거부를 유지한다`() {
        // when
        val missing = mvc.perform(get("/api/v1/users/me"))
        val invalid = mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer invalid"))

        // then
        missing.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401))
        invalid.andExpect(status().isUnauthorized())
        confirmVerified(shelters)
    }
}
