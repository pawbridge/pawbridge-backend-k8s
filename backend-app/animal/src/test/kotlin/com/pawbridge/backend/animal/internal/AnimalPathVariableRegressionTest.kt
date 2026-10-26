package com.pawbridge.backend.animal.internal

import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery
import com.pawbridge.backend.animal.internal.discovery.web.AnimalDiscoveryController
import com.pawbridge.backend.animal.internal.discovery.web.AnimalDiscoveryExceptionHandler
import com.pawbridge.backend.animal.internal.recommendation.application.AnimalRecommendations
import com.pawbridge.backend.animal.internal.recommendation.web.AnimalRecommendationController
import com.pawbridge.backend.animal.internal.recommendation.web.AnimalRecommendationExceptionHandler
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads
import com.pawbridge.backend.animal.internal.shelter.web.ShelterReadController
import com.pawbridge.backend.animal.internal.shelter.web.ShelterReadExceptionHandler
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.MissingPathVariableException
import java.nio.charset.StandardCharsets

/**
 * Locks in the Java behaviour of animal path variables after the Java to Kotlin conversion.
 *
 * A Kotlin `@PathVariable id: Long?` makes Spring treat the variable as optional, so a blank
 * segment (`/%20`) reached the service as null. The Java original declared a required boxed
 * `Long`, which fails with [MissingPathVariableException] before the handler runs. Every path
 * variable of the controllers below must keep that behaviour.
 *
 * The services are strict mocks. For a numeric id the stub only has to let the handler finish, so those tests
 * assert only the service call, not the response.
 *
 * Standalone MockMvc with the module exception handlers and mocked services only. It does not cover the
 * servlet container, the security filter chain or error dispatch.
 */
class AnimalPathVariableRegressionTest {
    private val animals = mockk<AnimalDiscovery>()
    private val recommendations = mockk<AnimalRecommendations>()
    private val shelters = mockk<ShelterReads>()

    private lateinit var discoveryMvc: MockMvc
    private lateinit var recommendationMvc: MockMvc
    private lateinit var shelterMvc: MockMvc

    @BeforeEach
    fun setUp() {
        discoveryMvc = MockMvcBuilders
            .standaloneSetup(AnimalDiscoveryController(animals))
            .setControllerAdvice(AnimalDiscoveryExceptionHandler())
            .build()
        recommendationMvc = MockMvcBuilders
            .standaloneSetup(AnimalRecommendationController(recommendations))
            .setControllerAdvice(AnimalRecommendationExceptionHandler())
            .build()
        shelterMvc = MockMvcBuilders
            .standaloneSetup(ShelterReadController(shelters))
            .setControllerAdvice(ShelterReadExceptionHandler())
            .build()
    }

    // AnimalDiscoveryController

    @Test
    fun `공백 동물 ID가 주어졌을 때__동물 상세를 조회하면__서비스 호출 없이 경로 변수 누락 오류로 실패한다`() {
        // given
        // when
        val result = discoveryMvc.perform(get("/api/v1/animals/{id}", BLANK_SEGMENT)).andReturn()

        // then
        assertJavaOriginalServerError(result)
        confirmVerified(animals)
    }

    @Test
    fun `숫자 동물 ID가 주어졌을 때__동물 상세를 조회하면__그 ID를 서비스에 넘긴다`() {
        // given
        every { animals.findById(any()) } returns animalDetails()

        // when
        discoveryMvc.perform(get("/api/v1/animals/{id}", ID))

        // then
        verify(exactly = 1) { animals.findById(ID) }
    }

    // AnimalRecommendationController

    @Test
    fun `공백 동물 ID가 주어졌을 때__유사 동물을 조회하면__서비스 호출 없이 경로 변수 누락 오류로 실패한다`() {
        // given
        // when
        val result = recommendationMvc.perform(get("/api/v1/animals/{id}/similar", BLANK_SEGMENT)).andReturn()

        // then
        assertJavaOriginalServerError(result)
        confirmVerified(recommendations)
    }

    @Test
    fun `숫자 동물 ID가 주어졌을 때__유사 동물을 조회하면__그 ID를 서비스에 넘긴다`() {
        // given
        every { recommendations.recommend(any()) } returns emptyList()

        // when
        recommendationMvc.perform(get("/api/v1/animals/{id}/similar", ID))

        // then
        verify(exactly = 1) { recommendations.recommend(ID) }
    }

    // ShelterReadController

    @Test
    fun `공백 보호소 ID가 주어졌을 때__보호소 상세를 조회하면__서비스 호출 없이 경로 변수 누락 오류로 실패한다`() {
        // given
        // when
        val result = shelterMvc.perform(get("/api/v1/shelters/{id}", BLANK_SEGMENT)).andReturn()

        // then
        assertJavaOriginalServerError(result)
        confirmVerified(shelters)
    }

    @Test
    fun `숫자 보호소 ID가 주어졌을 때__보호소 상세를 조회하면__그 ID를 서비스에 넘긴다`() {
        // given
        every { shelters.findById(any()) } returns shelterDetails()

        // when
        shelterMvc.perform(get("/api/v1/shelters/{id}", ID))

        // then
        verify(exactly = 1) { shelters.findById(ID) }
    }

    // helpers

    private fun animalDetails(): AnimalDiscovery.Details {
        return AnimalDiscovery.Details(
            id = ID,
            apmsNoticeNo = null,
            species = null,
            breed = null,
            gender = null,
            neuterStatus = null,
            birthYear = null,
            age = null,
            weight = null,
            color = null,
            specialMark = null,
            status = null,
            noticeStartDate = null,
            noticeEndDate = null,
            happenPlace = null,
            happenDate = null,
            imageUrl = null,
            imageUrl2 = null,
            description = null,
            favoriteCount = null,
            shelterId = null,
            shelterName = null,
            apmsDesertionNo = null,
            apmsProcessState = null,
            apmsUpdatedAt = null,
            apiSource = null,
            createdAt = null,
            updatedAt = null,
        )
    }

    private fun shelterDetails(): ShelterReads.Details {
        return ShelterReads.Details(
            id = ID,
            careRegNo = null,
            name = null,
            phone = null,
            address = null,
            ownerName = null,
            organizationName = null,
            email = null,
            introduction = null,
            adoptionProcedure = null,
            operatingHours = null,
            createdAt = null,
            updatedAt = null,
            publicInformation = null,
        )
    }

    /** Java original: 500, the handler's generic error body, and MissingPathVariableException as the resolved exception. */
    private fun assertJavaOriginalServerError(result: MvcResult) {
        assertThat(result.response.status).isEqualTo(500)
        assertThat(result.response.getContentAsString(StandardCharsets.UTF_8)).isEqualTo(SERVER_ERROR_BODY)
        assertThat(result.resolvedException).isInstanceOf(MissingPathVariableException::class.java)
    }

    companion object {
        /** Expanded into the URI template, so it reaches Spring as the blank segment `%20`. */
        private const val BLANK_SEGMENT = " "
        private const val ID = 10L

        /** Body written by the module exception handlers for the Java original. */
        private const val SERVER_ERROR_BODY = "{\"code\":500,\"message\":\"서버 내부 오류가 발생했습니다.\",\"data\":null}"
    }
}
