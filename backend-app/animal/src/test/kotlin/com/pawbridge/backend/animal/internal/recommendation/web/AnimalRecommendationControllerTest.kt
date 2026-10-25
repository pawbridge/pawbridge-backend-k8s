package com.pawbridge.backend.animal.internal.recommendation.web

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.AnimalStatus
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Gender
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.Species
import com.pawbridge.backend.animal.internal.discovery.application.AnimalNotFoundException
import com.pawbridge.backend.animal.internal.recommendation.application.AnimalRecommendations
import com.pawbridge.backend.animal.internal.recommendation.application.AnimalRecommendations.Card
import com.pawbridge.backend.animal.internal.recommendation.application.RecommendationUnavailableException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.stream.Stream

class AnimalRecommendationControllerTest {
    private val recommendations: AnimalRecommendations = mock(AnimalRecommendations::class.java)
    private val mapper: ObjectMapper = ObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    private val mvc: MockMvc = MockMvcBuilders.standaloneSetup(AnimalRecommendationController(recommendations))
        .setControllerAdvice(AnimalRecommendationExceptionHandler())
        .setMessageConverters(MappingJackson2HttpMessageConverter(mapper))
        .build()

    @Test
    fun givenStoredCardFields__whenGettingSimilarAnimals__thenReturnOriginalArrayWithAll18FieldsInOrder() {
        `when`(recommendations.recommend(1)).thenReturn(
            listOf(
                Card(
                    4L, "공고-4", Species.DOG, "믹스", Gender.FEMALE,
                    2023, 3, "0.69(Kg)", "레몬색&흰색", LocalDate.of(2026, 9, 29), "귀 반점", AnimalStatus.PROTECT,
                    LocalDate.of(2026, 10, 9), "https://example.test/a.jpg", 7, 8L, "보호소",
                    LocalDateTime.of(2026, 9, 29, 13, 4, 5),
                ),
            ),
        )

        val actual: JsonNode = json(
            mvc.perform(get("/api/v1/animals/1/similar")).andExpect(status().isOk()).andReturn()
                .response.getContentAsString(StandardCharsets.UTF_8),
        )

        assertThat(actual.isArray()).isTrue()
        assertThat(actual.size()).isOne()
        assertThat(actual.get(0)).isEqualTo(
            mapper.readTree(
                """
                {"id":4,"apmsNoticeNo":"공고-4","species":"DOG","breed":"믹스","gender":"FEMALE",
                "birthYear":2023,"age":3,"weight":"0.69(Kg)","color":"레몬색&흰색","happenDate":"2026-09-29",
                "specialMark":"귀 반점","status":"PROTECT","noticeEndDate":"2026-10-09",
                "imageUrl":"https://example.test/a.jpg","favoriteCount":7,"shelterId":8,
                "shelterName":"보호소","createdAt":"2026-09-29T13:04:05"}
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun givenMissingOptionalFieldsOrNoCandidates__whenGettingSimilarAnimals__thenOmitNullFieldsOrReturnEmptyArray() {
        `when`(recommendations.recommend(1)).thenReturn(
            listOf(
                Card(
                    4L, null, Species.CAT, null, null, null, null,
                    null, null, null, null, AnimalStatus.NOTICE, null, null, null, null, null, null,
                ),
            ),
        )

        val response = mvc.perform(get("/api/v1/animals/1/similar")).andExpect(status().isOk()).andReturn().response

        assertThat(json(response.contentAsString))
            .isEqualTo(mapper.readTree("[{\"id\":4,\"species\":\"CAT\",\"status\":\"NOTICE\"}]"))
        `when`(recommendations.recommend(1)).thenReturn(listOf())
        assertThat(
            mvc.perform(get("/api/v1/animals/1/similar")).andExpect(status().isOk()).andReturn().response
                .contentAsString,
        ).isEqualTo("[]")
    }

    @ParameterizedTest
    @MethodSource("failures")
    fun givenSourceOrUpstreamOrDatabaseFailure__whenGettingSimilarAnimals__thenKeepOriginalStatusAndSafeEnvelope(
        failure: RuntimeException,
        code: Int,
        message: String,
    ) {
        `when`(recommendations.recommend(1)).thenThrow(failure)

        val response = mvc.perform(get("/api/v1/animals/1/similar")).andExpect(status().`is`(code)).andReturn().response

        val actual = json(response.getContentAsString(StandardCharsets.UTF_8))
        assertThat(actual).isEqualTo(mapper.createObjectNode().put("code", code).put("message", message).putNull("data"))
    }

    @Test
    fun givenNonNumericId__whenBindingPath__thenKeepSourceCatchAll500WithoutCallingApplication() {
        mvc.perform(get("/api/v1/animals/invalid/similar")).andExpect(status().isInternalServerError())

        verifyNoInteractions(recommendations)
    }

    private fun json(source: String): JsonNode {
        return mapper.readTree(source)
    }

    companion object {
        @JvmStatic
        fun failures(): Stream<Arguments> = Stream.of(
            Arguments.of(AnimalNotFoundException(), 404, "동물을 찾을 수 없습니다"),
            Arguments.of(RecommendationUnavailableException(), 503, "추천 자료를 준비 중입니다. 잠시 후 다시 시도해 주세요"),
            Arguments.of(
                DataAccessResourceFailureException("synthetic private database detail"),
                500,
                "데이터베이스 오류가 발생했습니다.",
            ),
            Arguments.of(IllegalStateException("synthetic private failure"), 500, "서버 내부 오류가 발생했습니다."),
        )
    }
}
