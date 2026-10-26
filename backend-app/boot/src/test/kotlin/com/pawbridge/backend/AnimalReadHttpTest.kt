package com.pawbridge.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery
import com.pawbridge.backend.animal.internal.discovery.application.AnimalNotFoundException
import com.pawbridge.backend.animal.internal.discovery.web.AnimalDiscoveryController
import com.pawbridge.backend.animal.internal.discovery.web.AnimalDiscoveryExceptionHandler
import com.pawbridge.backend.animal.internal.recommendation.application.AnimalRecommendations
import com.pawbridge.backend.animal.internal.recommendation.web.AnimalRecommendationController
import com.pawbridge.backend.animal.internal.recommendation.web.AnimalRecommendationExceptionHandler
import com.pawbridge.backend.boot.configuration.ReadSecurityConfiguration
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate

@WebMvcTest(
    properties = [
        "pawbridge.animal-discovery.enabled=true",
        "pawbridge.animal-recommendations.enabled=true",
        "pawbridge.animal-query.backend=postgresql",
    ],
    excludeAutoConfiguration = [UserDetailsServiceAutoConfiguration::class],
)
@ContextConfiguration(
    classes = [
        AnimalDiscoveryController::class,
        AnimalDiscoveryExceptionHandler::class,
        AnimalRecommendationController::class,
        AnimalRecommendationExceptionHandler::class,
        ReadSecurityConfiguration::class,
        F3TestInputs.SecurityInputs::class,
    ],
)
class AnimalReadHttpTest {
    @Autowired
    lateinit var mvc: MockMvc

    @MockkBean
    lateinit var animals: AnimalDiscovery

    @MockkBean
    lateinit var recommendations: AnimalRecommendations

    @Test
    fun `공개 조회가 켜졌을 때__익명이나 잘못된 토큰으로 정확한 경로를 요청하면__세션 없이 소유 컨트롤러에 닿는다`() {
        // given
        every { animals.search(any(), any()) } returns AnimalDiscovery.Page(listOf(), 0, 20, 0, 0, true, true)
        every { animals.findById(42) } throws AnimalNotFoundException()
        every { recommendations.recommend(42) } returns listOf()

        // when
        val list = mvc.perform(get("/api/v1/animals").param("species", "DOG").header("Authorization", "Bearer invalid"))
        val detail = mvc.perform(get("/api/v1/animals/42").param("view", "detail"))
        val similar = mvc.perform(
            get("/api/v1/animals/42/similar").param("view", "cards").header("Authorization", "Bearer invalid"),
        )

        // then
        list.andExpect(status().isOk())
            .andExpect(header().doesNotExist("Set-Cookie"))
        detail.andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value(404))
        similar.andExpect(status().isOk())
            .andExpect(content().json("[]"))
            .andExpect(header().doesNotExist("Set-Cookie"))
        verify(exactly = 1) { animals.search(any(), any()) }
        verify(exactly = 1) { animals.findById(42) }
        verify(exactly = 1) { recommendations.recommend(42) }
    }

    @Test
    fun `APMS 조회가 켜졌을 때__익명이나 잘못된 토큰으로 정확한 번호를 읽으면__세션 없이 기존 28개 필드 상세를 돌려준다`() {
        // given
        val date = LocalDate.of(2026, 1, 2)
        val time = date.atTime(3, 4, 5, 123456000)

        every { animals.findByApmsDesertionNo("D-42") } returns AnimalDiscovery.Details(
            42L, "N-42",
            AnimalDiscovery.Species.DOG, "말티즈", AnimalDiscovery.Gender.FEMALE, AnimalDiscovery.NeuterStatus.YES,
            2022, 4, "3.5(Kg)", "흰색", "특징", AnimalDiscovery.AnimalStatus.ADOPTED, date, date.plusDays(3),
            "합성 장소", date, "one", "two", "설명", 0, 8L, "합성 보호소", "D-42", "종료(입양)", time,
            AnimalDiscovery.ApiSource.APMS_ANIMAL, time, time,
        )

        // when
        val response = mvc.perform(get("/api/v1/animals/apms/D-42").header("Authorization", "Bearer invalid"))
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(jsonPath("$.id").value(42))
            .andExpect(jsonPath("$.status").value("ADOPTED"))
            .andExpect(jsonPath("$.apmsDesertionNo").value("D-42"))
            .andExpect(jsonPath("$.updatedAt").value("2026-01-02T03:04:05.123456"))
            .andReturn().response

        // then
        val body = ObjectMapper().readTree(response.contentAsString)
        val fields = HashSet<String>()
        body.fieldNames().forEachRemaining { name -> fields.add(name) }
        assertEquals(
            setOf(
                "id", "apmsNoticeNo", "species", "breed", "gender",
                "neuterStatus", "birthYear", "age", "weight", "color", "specialMark", "status", "noticeStartDate",
                "noticeEndDate", "happenPlace", "happenDate", "imageUrl", "imageUrl2", "description", "favoriteCount",
                "shelterId", "shelterName", "apmsDesertionNo", "apmsProcessState", "apmsUpdatedAt", "apiSource",
                "createdAt", "updatedAt",
            ),
            fields,
        )
        verify(exactly = 1) { animals.findByApmsDesertionNo("D-42") }
        confirmVerified(animals, recommendations)
    }

    @Test
    fun `만료 임박 조회가 켜졌을 때__익명이 기본 페이지를 읽으면__원래 기본값과 7개 필드 응답을 쓴다`() {
        // given
        every { animals.findExpiringSoonAnimals(any()) } returns AnimalDiscovery.Page(listOf(), 0, 20, 0, 0, true, true)

        // when
        val response = mvc.perform(get("/api/v1/animals/expiring-soon"))

        // then
        response.andExpect(status().isOk())
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(
                content().json(
                    "{\"content\":[],\"number\":0,\"size\":20,\"totalElements\":0,\"totalPages\":0,\"first\":true,\"last\":true}",
                    true,
                ),
            )
        verify(exactly = 1) {
            animals.findExpiringSoonAnimals(
                AnimalDiscovery.PageRequest(0, 20, listOf(AnimalDiscovery.Order("noticeEndDate", true)), true),
            )
        }
        confirmVerified(animals, recommendations)
    }

    @Test
    fun `추가 조회 오류가 났을 때__공개 질의가 컨트롤러에 닿으면__404와 비공개 데이터베이스 500을 유지한다`() {
        // given
        every { animals.findByApmsDesertionNo("missing") } throws AnimalNotFoundException()
        every { animals.findExpiringSoonAnimals(any()) } throws DataAccessResourceFailureException("synthetic private SQL")

        // when
        val missing = mvc.perform(get("/api/v1/animals/apms/missing"))
        val failing = mvc.perform(get("/api/v1/animals/expiring-soon"))

        // then
        missing.andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value(404))
            .andExpect(jsonPath("$.message").value("동물을 찾을 수 없습니다"))
        failing.andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value(500))
            .andExpect(jsonPath("$.message").value("데이터베이스 오류가 발생했습니다."))
        verify(exactly = 1) { animals.findByApmsDesertionNo("missing") }
        verify(exactly = 1) { animals.findExpiringSoonAnimals(any()) }
        confirmVerified(animals, recommendations)
    }
}
