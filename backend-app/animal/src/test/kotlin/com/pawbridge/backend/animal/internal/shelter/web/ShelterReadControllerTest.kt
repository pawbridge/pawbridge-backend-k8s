package com.pawbridge.backend.animal.internal.shelter.web

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.pawbridge.backend.animal.anyNonNull
import com.pawbridge.backend.animal.internal.shelter.application.ShelterNotFoundException
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads.Details
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads.Discovery
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads.Observation
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads.Order
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads.Page
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads.PageRequest
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads.Preview
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads.PublicInformation
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads.Search
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads.Summary
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.data.web.PageableHandlerMethodArgumentResolver
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.stream.Stream

class ShelterReadControllerTest {
    private val shelters: ShelterReads = mock(ShelterReads::class.java)
    private val mapper: ObjectMapper = ObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    private val mvc: MockMvc = MockMvcBuilders
        .standaloneSetup(ShelterReadController(shelters), ShelterDiscoveryReadController(shelters))
        .setControllerAdvice(ShelterReadExceptionHandler())
        .setCustomArgumentResolvers(PageableHandlerMethodArgumentResolver())
        .setMessageConverters(MappingJackson2HttpMessageConverter(mapper))
        .build()

    @Test
    fun givenOrdinaryList__whenListing__thenKeepDefaultPageSortFullPageMetadataAndSevenSummaryFields() {
        val created = LocalDateTime.of(2026, 9, 29, 13, 4, 5)
        `when`(shelters.search(anyNonNull<Search>(), anyNonNull<PageRequest>()))
            .thenReturn(Page(listOf(Summary(4L, "reg-4", "센터", "02", "서울", "시청", created)), 1))

        val actual: JsonNode = response("/api/v1/shelters")

        verify(shelters).search(Search(null, null, null, null), PageRequest(0, 20, listOf(Order("name", true, false))))
        assertThat(actual.properties().map { entry -> entry.key }).containsExactlyInAnyOrder(
            "content", "pageable", "last", "totalPages", "totalElements",
            "size", "number", "sort", "first", "numberOfElements", "empty",
        )
        assertThat(actual.path("content").get(0)).isEqualTo(
            mapper.readTree(
                """
                {"id":4,"careRegNo":"reg-4","name":"센터","phone":"02","address":"서울","organizationName":"시청","createdAt":"2026-09-29T13:04:05"}
                """.trimIndent(),
            ),
        )
        assertThat(actual.path("size").asInt()).isEqualTo(20)
        assertThat(actual.path("totalElements").asLong()).isOne()
        assertThat(actual.path("pageable").path("pageSize").asInt()).isEqualTo(20)
        assertThat(actual.path("sort").path("sorted").asBoolean()).isTrue()
        assertThat(actual.path("first").asBoolean()).isTrue()
        assertThat(actual.path("last").asBoolean()).isTrue()
    }

    @Test
    fun givenAllFiltersAndCustomPageSort__whenListing__thenForwardRawSearchTextAndAllOrdersWithoutCombiningFilters() {
        `when`(shelters.search(anyNonNull<Search>(), anyNonNull<PageRequest>())).thenReturn(Page(listOf(), 0))

        mvc.perform(
            get("/api/v1/shelters").param("name", "name").param("keyword", " 센터 ").param("address", " 서울 ")
                .param("organizationName", "org").param("page", "2").param("size", "12")
                .param("sort", "name,desc,ignorecase", "createdAt,asc"),
        ).andExpect(status().isOk())

        verify(shelters).search(
            Search("name", " 서울 ", "org", " 센터 "),
            PageRequest(
                2,
                12,
                listOf(Order("name", false, true), Order("createdAt", true, false)),
            ),
        )
    }

    @Test
    fun givenDetailAndRegistrationLookup__whenReading__thenReturnAll14FieldsAndOriginalPublicInformation() {
        val created = LocalDateTime.of(2026, 9, 29, 13, 4, 5)
        val updated = created.plusDays(1)
        val publicInformation = PublicInformation(
            "APMS_SHELTER_INFO", "02-public", 37.5, 127.1, "09", "18", "10", "16", "월", "2026-09-29", updated,
        )
        val detail = Details(
            4L, "reg-4", "센터", "02", "서울", "대표", "시청", "sample@example.test", "소개", "절차", "09-18",
            created, updated, publicInformation,
        )
        `when`(shelters.findById(4L)).thenReturn(detail)
        `when`(shelters.findByCareRegNo("reg-4")).thenReturn(detail)
        val expected: JsonNode = mapper.readTree(
            """
            {"id":4,"careRegNo":"reg-4","name":"센터","phone":"02","address":"서울","ownerName":"대표","organizationName":"시청",
            "email":"sample@example.test","introduction":"소개","adoptionProcedure":"절차","operatingHours":"09-18",
            "createdAt":"2026-09-29T13:04:05","updatedAt":"2026-09-30T13:04:05",
            "publicInformation":{"source":"APMS_SHELTER_INFO","phone":"02-public","latitude":37.5,"longitude":127.1,
            "weekdayOpen":"09","weekdayClose":"18","weekendOpen":"10","weekendClose":"16","closedDays":"월",
            "sourceUpdatedDate":"2026-09-29","collectedAt":"2026-09-30T13:04:05"}}
            """.trimIndent(),
        )

        assertThat(response("/api/v1/shelters/4")).isEqualTo(expected)
        assertThat(response("/api/v1/shelters/by-care-reg-no/reg-4")).isEqualTo(expected)
    }

    @Test
    fun givenNullableDetailFieldsAndScalarCount__whenReading__thenOmitNullsAndReturnLongWithoutEnvelope() {
        `when`(shelters.findById(4L)).thenReturn(
            Details(4L, "reg-4", "센터", null, null, null, null, null, null, null, null, null, null, null),
        )

        assertThat(response("/api/v1/shelters/4"))
            .isEqualTo(mapper.readTree("{\"id\":4,\"careRegNo\":\"reg-4\",\"name\":\"센터\"}"))
        `when`(shelters.count()).thenReturn(9876543210L)
        assertThat(response("/api/v1/shelters/count").asLong()).isEqualTo(9876543210L)
    }

    @Test
    fun givenDiscoveryDatesFiltersAndPage__whenDiscovering__thenBindOriginalInputsAndIncludeNullablePreviewFields() {
        `when`(shelters.discover(anyString(), anyString(), any(), any(), anyInt(), anyInt())).thenReturn(
            Page(
                listOf(
                    Discovery(
                        4, "reg", "센터", null, null, 1,
                        listOf(Preview(40, null, "DOG", "UNKNOWN", null, null, LocalDate.of(2026, 9, 29))),
                    ),
                ),
                99,
            ),
        )

        val result = mvc.perform(
            get("/api/v1/shelters/discovery").param("intakeFrom", "2026-09-01").param("intakeTo", "2026-09-30")
                .param("keyword", "보호소").param("address", "서울").param("page", "2"),
        ).andExpect(status().isOk()).andReturn().response

        verify(shelters).discover("보호소", "서울", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), 2, 12)
        val actual: JsonNode = mapper.readTree(result.getContentAsString(StandardCharsets.UTF_8))
        assertThat(actual.path("content").get(0)).isEqualTo(
            mapper.readTree(
                """
                {"id":4,"careRegNo":"reg","name":"센터","address":null,"phone":null,"protectedCount":1,
                "animals":[{"id":40,"breed":null,"species":"DOG","gender":"UNKNOWN","birthYear":null,"imageUrl":null,"happenDate":"2026-09-29"}]}
                """.trimIndent(),
            ),
        )
        assertThat(actual.path("number").asInt()).isEqualTo(2)
        assertThat(actual.path("size").asInt()).isEqualTo(12)
        assertThat(actual.path("totalElements").asLong()).isEqualTo(99)
        assertThat(actual.path("sort").path("unsorted").asBoolean()).isTrue()
    }

    @Test
    fun givenNoIntakeDates__whenDiscovering__thenDefaultToInclusiveThirtyDaysEndingTodayInSeoul() {
        `when`(shelters.discover(anyString(), anyString(), any(), any(), anyInt(), anyInt()))
            .thenReturn(Page(listOf(), 0))

        response("/api/v1/shelters/discovery")

        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
        verify(shelters).discover("", "", today.minusDays(29), today, 0, 12)
    }

    @Test
    fun givenOnlyOneIntakeBound__whenDiscovering__thenForwardNullOtherBoundForOriginalRangeValidation() {
        `when`(shelters.discover(anyString(), anyString(), any(), any(), anyInt(), anyInt()))
            .thenThrow(IllegalArgumentException("접수일은 시작일과 종료일을 함께 지정하고 최대 366일 이내로 선택해주세요."))

        mvc.perform(get("/api/v1/shelters/discovery").param("intakeFrom", "2026-09-01"))
            .andExpect(status().isBadRequest())

        verify(shelters).discover("", "", LocalDate.of(2026, 9, 1), null, 0, 12)
    }

    @ParameterizedTest
    @MethodSource("malformed")
    fun givenMalformedDiscoveryParameter__whenBinding__thenKeepLocalEmpty400WithoutCallingApplication(
        route: String,
        parameter: String?,
        value: String?,
    ) {
        val request = get(route)
        if (parameter != null) {
            request.param(parameter, value)
        }

        val result = mvc.perform(request).andExpect(status().isBadRequest()).andReturn().response

        assertThat(result.contentAsString).isEmpty()
        verifyNoInteractions(shelters)
    }

    @Test
    fun givenRecordedObservationsOrEmptySeries__whenReading__thenKeepDateOffsetAndAbsentDaysWithoutZeroFill() {
        val observed = Observation(LocalDate.of(2026, 9, 3), OffsetDateTime.parse("2026-09-03T10:11:12+09:00"), 8)
        `when`(shelters.observations(4L, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)))
            .thenReturn(listOf(observed))
        val route = "/api/v1/shelters/4/observations?from=2026-09-01&to=2026-09-30"

        assertThat(response(route)).isEqualTo(
            mapper.readTree("[{\"date\":\"2026-09-03\",\"observedAt\":\"2026-09-03T10:11:12+09:00\",\"protectedCount\":8}]"),
        )
        `when`(shelters.observations(4L, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30))).thenReturn(listOf())
        assertThat(response(route)).isEqualTo(mapper.createArrayNode())
    }

    @Test
    fun givenMissingObservationDateParameter__whenBinding__thenKeepGlobal400Envelope() {
        val result = mvc.perform(get("/api/v1/shelters/4/observations").param("to", "2026-09-30"))
            .andExpect(status().isBadRequest()).andReturn().response

        assertThat(mapper.readTree(result.getContentAsString(StandardCharsets.UTF_8))).isEqualTo(
            mapper.createObjectNode().put("code", 400).put("message", "필수 요청 파라미터 'from'(이)가 누락되었습니다.").putNull("data"),
        )
        verifyNoInteractions(shelters)
    }

    @ParameterizedTest
    @MethodSource("failures")
    fun givenReadFailure__whenGetting__thenKeepSourceStatusAndSafeEnvelope(
        failure: RuntimeException,
        observations: Boolean,
        code: Int,
        message: String,
    ) {
        val route: String
        if (observations) {
            `when`(shelters.observations(anyLong(), any(), any())).thenThrow(failure)
            route = "/api/v1/shelters/4/observations?from=2026-09-01&to=2026-09-30"
        } else {
            `when`(shelters.findById(4L)).thenThrow(failure)
            route = "/api/v1/shelters/4"
        }

        val result = mvc.perform(get(route)).andExpect(status().`is`(code)).andReturn().response

        assertThat(mapper.readTree(result.getContentAsString(StandardCharsets.UTF_8)))
            .isEqualTo(mapper.createObjectNode().put("code", code).put("message", message).putNull("data"))
    }

    @Test
    fun givenMalformedOrdinaryDetailId__whenBinding__thenKeepGeneric500AndNoApplicationCall() {
        mvc.perform(get("/api/v1/shelters/invalid")).andExpect(status().isInternalServerError())

        verifyNoInteractions(shelters)
    }

    private fun response(route: String): JsonNode {
        val result = mvc.perform(get(route)).andExpect(status().isOk()).andReturn().response
        return mapper.readTree(result.getContentAsString(StandardCharsets.UTF_8))
    }

    companion object {
        @JvmStatic
        fun malformed(): Stream<Arguments> = Stream.of(
            Arguments.of("/api/v1/shelters/discovery", "intakeFrom", "not-a-date"),
            Arguments.of("/api/v1/shelters/discovery", "page", "not-a-number"),
            Arguments.of("/api/v1/shelters/invalid/observations?from=2026-09-01&to=2026-09-30", null, null),
        )

        @JvmStatic
        fun failures(): Stream<Arguments> = Stream.of(
            Arguments.of(IllegalStateException("Shelter not found: 4"), false, 500, "서버 내부 오류가 발생했습니다."),
            Arguments.of(ShelterNotFoundException(), true, 404, "보호소를 찾을 수 없습니다"),
            Arguments.of(IllegalArgumentException("invalid range"), true, 400, "invalid range"),
            Arguments.of(
                DataAccessResourceFailureException("synthetic private detail"),
                false,
                500,
                "데이터베이스 오류가 발생했습니다.",
            ),
        )
    }
}
