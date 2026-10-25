package com.pawbridge.backend.animal.internal.lostsearch.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.pawbridge.backend.animal.anyNonNull
import com.pawbridge.backend.animal.internal.command.infrastructure.dto.response.AnimalDetailResponse
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.Species
import com.pawbridge.backend.animal.internal.lostsearch.infrastructure.LostSearchRequest
import com.pawbridge.backend.animal.internal.lostsearch.infrastructure.LostSearchResponse
import com.pawbridge.backend.animal.internal.lostsearch.infrastructure.LostSearchService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.server.ResponseStatusException

class LostSearchControllerTest {
    private val service: LostSearchService = mock(LostSearchService::class.java)
    private val json = ObjectMapper()
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setUp() {
        mvc = MockMvcBuilders.standaloneSetup(LostSearchController(service)).build()
    }

    @Test
    fun multipart_post_binds_original_request_and_returns_candidate_without_image_score() {
        val animal = AnimalDetailResponse.builder().id(7L).species(Species.DOG).build()
        `when`(service.search(anyNonNull())).thenAnswer { invocation ->
            val request: LostSearchRequest = invocation.getArgument(0)
            assertThat(request.species).isEqualTo(Species.DOG)
            assertThat(request.image!!.bytes).isEqualTo(byteArrayOf(1))
            assertThat(request.region).isEqualTo("서울")
            LostSearchResponse(
                listOf(LostSearchResponse.Candidate(animal, "02-0000", listOf("DISCOVERY_PLACE_TEXT_MATCH"))),
            )
        }

        val response = mvc.perform(
            multipart("/api/v1/animals/lost-candidates")
                .file(MockMultipartFile("image", byteArrayOf(1)))
                .param("species", "DOG")
                .param("region", "서울"),
        ).andReturn().response

        assertThat(response.status).isEqualTo(200)
        val result = json.readTree(response.contentAsByteArray)
        val candidate = result.get("candidates").get(0)
        assertThat(candidate.get("animal").get("id").asLong()).isEqualTo(7L)
        assertThat(candidate.get("shelterPhone").asText()).isEqualTo("02-0000")
        assertThat(candidate.has("imageScore")).isFalse()
    }

    @Test
    fun invalid_date_binding_keeps_original_400_message_before_service() {
        val response = mvc.perform(
            multipart("/api/v1/animals/lost-candidates")
                .file(MockMultipartFile("image", byteArrayOf(1)))
                .param("species", "DOG")
                .param("lostDate", "invalid-date"),
        ).andReturn().response

        assertThat(response.status).isEqualTo(400)
        assertThat(json.readTree(response.contentAsByteArray).get("message").asText())
            .isEqualTo("사진·종류·날짜·지역·특징 입력을 확인해 주세요")
        verifyNoInteractions(service)
    }

    @ParameterizedTest
    @ValueSource(ints = [400, 413, 503])
    fun service_failure_keeps_status_message_and_only_503_retry_after(status: Int) {
        `when`(service.search(anyNonNull()))
            .thenThrow(ResponseStatusException(HttpStatus.valueOf(status), "synthetic-safe-message"))

        val response = mvc.perform(
            multipart("/api/v1/animals/lost-candidates")
                .file(MockMultipartFile("image", byteArrayOf(1)))
                .param("species", "DOG"),
        ).andReturn().response

        assertThat(response.status).isEqualTo(status)
        assertThat(json.readTree(response.contentAsByteArray).get("message").asText())
            .isEqualTo("synthetic-safe-message")
        assertThat(response.getHeader("Retry-After")).isEqualTo(if (status == 503) "3" else null)
    }
}
