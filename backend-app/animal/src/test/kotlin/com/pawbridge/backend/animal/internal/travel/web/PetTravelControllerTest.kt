package com.pawbridge.backend.animal.internal.travel.web

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.pawbridge.backend.animal.internal.travel.application.PetTravel
import com.pawbridge.backend.animal.internal.travel.application.PetTravelException
import com.pawbridge.backend.animal.internal.travel.application.PetTravelResponse
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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
    private val service: PetTravel = mockk<PetTravel>()
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setUp() {
        mvc = MockMvcBuilders.standaloneSetup(PetTravelController(service))
            .setMessageConverters(MappingJackson2HttpMessageConverter(ObjectMapper().registerModule(JavaTimeModule())))
            .build()
    }

    @Test
    fun `지역 목록이 있을 때__조회하면__공개 응답으로 직렬화한다`() {
        // given
        every { service.regions() } returns
            PetTravelResponse.Regions(listOf(PetTravelResponse.Region("1", "서울")), Instant.EPOCH)

        // when
        val result = mvc.perform(get("/api/v1/places/regions"))

        // then
        result.andExpect(status().isOk())
            .andExpect(jsonAt("/items/0/code", JSON.valueToTree("1")))
            .andExpect(jsonAt("/items/0/name", JSON.valueToTree("서울")))
    }

    @Test
    fun `지역을 지정했을 때__목록을 조회하면__지역을 바인딩하고 미리보기 경계를 노출한다`() {
        // given
        every { service.places(any(), any()) } returns PetTravelResponse.Places("1", listOf(), true, Instant.EPOCH)

        // when
        val result = mvc.perform(get("/api/v1/places").param("areaCode", "1"))

        // then
        result.andExpect(status().isOk())
            .andExpect(jsonAt("/previewOnly", JSON.valueToTree(true)))
            .andExpect(jsonAt("/items", JSON.createArrayNode()))
        verify(exactly = 1) { service.places("1", 0) }
    }

    @Test
    fun `지역이 잘못됐을 때__조회하면__입력을 되돌려주지 않고 400을 돌려준다`() {
        // given
        every { service.places(any(), any()) } throws PetTravelException(PetTravelException.Code.INVALID_REQUEST)

        // when
        val result = mvc.perform(get("/api/v1/places"))

        // then
        result.andExpect(status().isBadRequest())
            .andExpect(jsonAt("/code", JSON.valueToTree("PET_TRAVEL_INVALID_REQUEST")))
        verify(exactly = 1) { service.places(null, 0) }
    }

    @Test
    fun `페이지를 지정했을 때__조회하면__페이지 메타데이터를 바인딩하고 직렬화한다`() {
        // given
        every { service.places(any(), any()) } returns
            PetTravelResponse.Places("11", listOf(), true, Instant.EPOCH, "PARTIAL", 2, 10, 21, 3)

        // when
        val result = mvc.perform(get("/api/v1/places").param("areaCode", "11").param("page", "2"))

        // then
        result.andExpect(status().isOk()).andExpect(jsonAt("/page", JSON.valueToTree(2)))
            .andExpect(jsonAt("/size", JSON.valueToTree(10))).andExpect(jsonAt("/totalElements", JSON.valueToTree(21)))
            .andExpect(jsonAt("/totalPages", JSON.valueToTree(3)))
            .andExpect(jsonAt("/availability", JSON.valueToTree("PARTIAL")))
        verify(exactly = 1) { service.places("11", 2) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["abc", "1.5", "2147483648"])
    fun `잘못된 형식의 페이지일 때__조회하면__서비스 호출 없이 400을 돌려준다`(page: String) {
        // given

        // when
        val result = mvc.perform(get("/api/v1/places").param("areaCode", "11").param("page", page))

        // then
        result.andExpect(status().isBadRequest())
            .andExpect(jsonAt("/code", JSON.valueToTree("PET_TRAVEL_INVALID_REQUEST")))
        confirmVerified(service)
    }

    @Test
    fun `서비스를 쓸 수 없을 때__조회하면__503과 no-store를 돌려준다`() {
        // given
        every { service.regions() } throws PetTravelException.unavailable()

        // when
        val result = mvc.perform(get("/api/v1/places/regions"))

        // then
        result.andExpect(status().isServiceUnavailable())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonAt("/code", JSON.valueToTree("PET_TRAVEL_UNAVAILABLE")))
    }

    @Test
    fun `트랜잭션을 시작할 수 없을 때__목록을 조회하면__503과 no-store를 돌려준다`() {
        // given
        val mvcWithAdvice = MockMvcBuilders.standaloneSetup(PetTravelController(service)).build()

        every { service.places(any(), any()) } throws CannotCreateTransactionException(
            "Could not start transaction",
            SQLTransientConnectionException("Connection unavailable"),
        )

        // when
        val result = mvcWithAdvice.perform(get("/api/v1/places").param("areaCode", "11"))

        // then
        result.andExpect(status().isServiceUnavailable())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonAt("/code", JSON.valueToTree("PET_TRAVEL_UNAVAILABLE")))
        verify(exactly = 1) { service.places("11", 0) }
    }

    @Test
    fun `없는 장소일 때__조회하면__404를 돌려준다`() {
        // given
        every { service.detail(any()) } throws PetTravelException(PetTravelException.Code.NOT_FOUND)

        // when
        val result = mvc.perform(get("/api/v1/places/123"))

        // then
        result.andExpect(status().isNotFound())
            .andExpect(jsonAt("/code", JSON.valueToTree("PET_TRAVEL_NOT_FOUND")))
        verify(exactly = 1) { service.detail("123") }
    }

    @Test
    fun `저장된 방문 상세가 있을 때__상세를 조회하면__정보와 갤러리 계약을 직렬화한다`() {
        // given
        val place = PetTravelResponse.Place("123", "서울 공원", "서울", null)
        val conditions = PetTravelResponse.Conditions(null, "소형견", "목줄", null, null, null, null)
        val visit = PetTravelResponse.VisitInformation(
            "12", "02-123-4567", "연중", "09:00~18:00",
            "월요일", "가능", null, "무료", null, null, "해설 프로그램", "연중", null, null,
            null, listOf(PetTravelResponse.InformationItem("입장료", "무료")), "READY", Instant.EPOCH,
        )

        every { service.detail(any()) } returns PetTravelResponse.Detail(
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
        )

        // when
        val result = mvc.perform(get("/api/v1/places/123"))

        // then
        result.andExpect(status().isOk())
            .andExpect(jsonAt("/visitInformation/usageHours", JSON.valueToTree("09:00~18:00")))
            .andExpect(jsonAt("/visitInformation/experienceGuide", JSON.valueToTree("해설 프로그램")))
            .andExpect(jsonAt("/visitInformation/additionalItems/0/name", JSON.valueToTree("입장료")))
            .andExpect(jsonAt("/images/0/name", JSON.valueToTree("전경")))
            .andExpect(jsonAt("/imagesStatus", JSON.valueToTree("READY")))
        verify(exactly = 1) { service.detail("123") }
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
