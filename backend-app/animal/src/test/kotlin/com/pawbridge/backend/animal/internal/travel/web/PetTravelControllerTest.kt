package com.pawbridge.backend.animal.internal.travel.web

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.pawbridge.backend.animal.internal.travel.application.PetTravel
import com.pawbridge.backend.animal.internal.travel.application.PetTravelException
import com.pawbridge.backend.animal.internal.travel.application.PetTravelResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultMatcher
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.CannotCreateTransactionException
import java.sql.SQLTransientConnectionException
import java.time.Instant

class PetTravelControllerTest {
    private val service: PetTravel = mock(PetTravel::class.java)
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setUp() {
        mvc = MockMvcBuilders.standaloneSetup(PetTravelController(service))
            .setMessageConverters(MappingJackson2HttpMessageConverter(ObjectMapper().registerModule(JavaTimeModule())))
            .build()
    }

    @Test
    fun givenRegionList__whenGet__thenSerializePublicResponse() {
        `when`(service.regions()).thenReturn(
            PetTravelResponse.Regions(listOf(PetTravelResponse.Region("1", "서울")), Instant.EPOCH),
        )

        mvc.perform(get("/api/v1/places/regions")).andExpect(status().isOk())
            .andExpect(jsonAt("/items/0/code", JSON.valueToTree("1")))
            .andExpect(jsonAt("/items/0/name", JSON.valueToTree("서울")))
    }

    @Test
    fun givenArea__whenList__thenBindAreaAndExposePreviewBoundary() {
        `when`(service.places("1", 0)).thenReturn(PetTravelResponse.Places("1", listOf(), true, Instant.EPOCH))

        mvc.perform(get("/api/v1/places").param("areaCode", "1")).andExpect(status().isOk())
            .andExpect(jsonAt("/previewOnly", JSON.valueToTree(true)))
            .andExpect(jsonAt("/items", JSON.createArrayNode()))
    }

    @Test
    fun givenInvalidArea__whenGet__thenReturn400WithoutInputEcho() {
        `when`(service.places(null, 0)).thenThrow(PetTravelException(PetTravelException.Code.INVALID_REQUEST))

        mvc.perform(get("/api/v1/places")).andExpect(status().isBadRequest())
            .andExpect(jsonAt("/code", JSON.valueToTree("PET_TRAVEL_INVALID_REQUEST")))
    }

    @Test
    fun givenPage__whenGet__thenBindAndSerializePageMetadata() {
        `when`(service.places("11", 2)).thenReturn(
            PetTravelResponse.Places("11", listOf(), true, Instant.EPOCH, "PARTIAL", 2, 10, 21, 3),
        )

        mvc.perform(get("/api/v1/places").param("areaCode", "11").param("page", "2"))
            .andExpect(status().isOk()).andExpect(jsonAt("/page", JSON.valueToTree(2)))
            .andExpect(jsonAt("/size", JSON.valueToTree(10))).andExpect(jsonAt("/totalElements", JSON.valueToTree(21)))
            .andExpect(jsonAt("/totalPages", JSON.valueToTree(3)))
            .andExpect(jsonAt("/availability", JSON.valueToTree("PARTIAL")))
    }

    @ParameterizedTest
    @ValueSource(strings = ["abc", "1.5", "2147483648"])
    fun givenMalformedPage__whenGet__then400WithoutServiceCall(page: String) {
        mvc.perform(get("/api/v1/places").param("areaCode", "11").param("page", page))
            .andExpect(status().isBadRequest())
            .andExpect(jsonAt("/code", JSON.valueToTree("PET_TRAVEL_INVALID_REQUEST")))

        verifyNoInteractions(service)
    }

    @Test
    fun givenUnavailable__whenGet__then503AndNoStore() {
        `when`(service.regions()).thenThrow(PetTravelException.unavailable())

        mvc.perform(get("/api/v1/places/regions")).andExpect(status().isServiceUnavailable())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonAt("/code", JSON.valueToTree("PET_TRAVEL_UNAVAILABLE")))
    }

    @Test
    fun givenTransactionCannotStart__whenList__then503AndNoStore() {
        `when`(service.places("11", 0)).thenThrow(
            CannotCreateTransactionException(
                "Could not start transaction",
                SQLTransientConnectionException("Connection unavailable"),
            ),
        )
        val mvcWithAdvice = MockMvcBuilders.standaloneSetup(PetTravelController(service)).build()

        mvcWithAdvice.perform(get("/api/v1/places").param("areaCode", "11"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonAt("/code", JSON.valueToTree("PET_TRAVEL_UNAVAILABLE")))
    }

    @Test
    fun givenMissingPlace__whenGet__then404() {
        `when`(service.detail("123")).thenThrow(PetTravelException(PetTravelException.Code.NOT_FOUND))

        mvc.perform(get("/api/v1/places/123")).andExpect(status().isNotFound())
            .andExpect(jsonAt("/code", JSON.valueToTree("PET_TRAVEL_NOT_FOUND")))
    }

    @Test
    fun givenStoredVisitDetails__whenGetDetail__thenSerializeInformationAndGalleryContract() {
        val place = PetTravelResponse.Place("123", "서울 공원", "서울", null)
        val conditions = PetTravelResponse.Conditions(null, "소형견", "목줄", null, null, null, null)
        val visit = PetTravelResponse.VisitInformation(
            "12", "02-123-4567", "연중", "09:00~18:00",
            "월요일", "가능", null, "무료", null, null, "해설 프로그램", "연중", null, null,
            null, listOf(PetTravelResponse.InformationItem("입장료", "무료")), "READY", Instant.EPOCH,
        )
        `when`(service.detail("123")).thenReturn(
            PetTravelResponse.Detail(
                place, "소개", conditions, true,
                "KOREA_TOURISM_ORGANIZATION", Instant.EPOCH, Instant.EPOCH, "READY", visit,
                listOf(
                    PetTravelResponse.Image(
                        "https://tong.visitkorea.or.kr/cms/resource/1/a.jpg",
                        null,
                        "전경",
                        "Type1",
                    ),
                ),
                "READY",
                Instant.EPOCH,
            ),
        )

        mvc.perform(get("/api/v1/places/123")).andExpect(status().isOk())
            .andExpect(jsonAt("/visitInformation/usageHours", JSON.valueToTree("09:00~18:00")))
            .andExpect(jsonAt("/visitInformation/experienceGuide", JSON.valueToTree("해설 프로그램")))
            .andExpect(jsonAt("/visitInformation/additionalItems/0/name", JSON.valueToTree("입장료")))
            .andExpect(jsonAt("/images/0/name", JSON.valueToTree("전경")))
            .andExpect(jsonAt("/imagesStatus", JSON.valueToTree("READY")))
    }

    private fun jsonAt(pointer: String, expected: JsonNode): ResultMatcher {
        return ResultMatcher { result ->
            assertThat(JSON.readTree(result.response.contentAsByteArray).at(pointer)).isEqualTo(expected)
        }
    }

    companion object {
        private val JSON: ObjectMapper = ObjectMapper().registerModule(JavaTimeModule())
    }
}
