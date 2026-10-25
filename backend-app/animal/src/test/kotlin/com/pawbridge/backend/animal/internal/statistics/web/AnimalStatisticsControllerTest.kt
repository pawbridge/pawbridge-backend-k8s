package com.pawbridge.backend.animal.internal.statistics.web

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.pawbridge.backend.animal.internal.statistics.application.AnimalStatistics
import com.pawbridge.backend.animal.internal.statistics.application.AnimalStatistics.DailyCount
import com.pawbridge.backend.animal.internal.statistics.application.AnimalStatistics.IntakeTrend
import com.pawbridge.backend.animal.internal.statistics.application.AnimalStatistics.RegionalCount
import com.pawbridge.backend.animal.internal.statistics.application.AnimalStatistics.Status
import com.pawbridge.backend.animal.internal.statistics.application.AnimalStatistics.StatusCount
import com.pawbridge.backend.animal.internal.statistics.application.AnimalStatistics.Today
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.util.stream.Stream

class AnimalStatisticsControllerTest {
    private val statistics: AnimalStatistics = mock(AnimalStatistics::class.java)
    private val mapper: ObjectMapper = ObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    private val mvc: MockMvc = MockMvcBuilders
        .standaloneSetup(AnimalStatisticsController(statistics), AdminAnimalStatisticsController(statistics))
        .setControllerAdvice(AnimalStatisticsExceptionHandler())
        .setMessageConverters(MappingJackson2HttpMessageConverter(mapper))
        .build()

    @Test
    fun givenTodayCounts__whenGettingPublicToday__thenKeepOriginalThreeFieldResponseAndLongCounts() {
        `when`(statistics.today()).thenReturn(Today(DAY, 9876543210L, 5L))

        assertThat(response("/api/v1/animals/stats/today")).isEqualTo(
            mapper.readTree("{\"date\":\"2026-10-02\",\"rescuedToday\":9876543210,\"adoptedToday\":5}"),
        )
    }

    @Test
    fun givenCurrentStatuses__whenGettingPublicStatus__thenKeepCodeCountLabelAndReturnedOrder() {
        `when`(statistics.status(null, null))
            .thenReturn(listOf(StatusCount(Status.PROTECT, 8L), StatusCount(Status.ADOPTED, 2L)))

        assertThat(response("/api/v1/animals/stats/status")).isEqualTo(
            mapper.readTree(
                """
                [{"status":"PROTECT","count":8,"label":"보호중"},{"status":"ADOPTED","count":2,"label":"종료(입양)"}]
                """.trimIndent(),
            ),
        )
        verify(statistics).status(null, null)
    }

    @Test
    fun givenExplicitOrMissingPublicBounds__whenBinding__thenPassRawDatesWithoutControllerDefaults() {
        `when`(statistics.status(any(), any())).thenReturn(listOf())
        `when`(statistics.regional(any(), any())).thenReturn(listOf(RegionalCount("서울", 9876543210L)))

        response("/api/v1/animals/stats/status?startDate=2026-10-02&endDate=2026-10-01")

        verify(statistics).status(DAY, DAY.minusDays(1))
        assertThat(response("/api/v1/animals/stats/regional?endDate=2026-10-02"))
            .isEqualTo(mapper.readTree("[{\"region\":\"서울\",\"count\":9876543210}]"))
        verify(statistics).regional(null, DAY)
    }

    @Test
    fun givenAdminIntakeTrend__whenGetting__thenKeepOriginalDailyAndPreviousDayContractWithoutFilledDates() {
        `when`(statistics.intakeTrend(DAY.minusDays(2), DAY)).thenReturn(
            IntakeTrend(
                DAY.minusDays(2),
                DAY,
                listOf(DailyCount(DAY.minusDays(2), 2L), DailyCount(DAY, 4L)),
                7,
            ),
        )

        assertThat(response("/api/v1/admin/stats/intake-trend?startDate=2026-09-30&endDate=2026-10-02"))
            .isEqualTo(
                mapper.readTree(
                    """
                    {"startDate":"2026-09-30","endDate":"2026-10-02","daily":[{"date":"2026-09-30","count":2},{"date":"2026-10-02","count":4}],"previousDayCount":7}
                    """.trimIndent(),
                ),
            )
    }

    @Test
    fun givenDailyRegistrationCounts__whenGettingAdminDaily__thenKeepArrayAndRawBoundariesWithoutIntakeValidation() {
        `when`(statistics.dailyAnimals(DAY, DAY.minusDays(1))).thenReturn(listOf(DailyCount(DAY, 9876543210L)))

        assertThat(response("/api/v1/admin/stats/daily-animals?startDate=2026-10-02&endDate=2026-10-01"))
            .isEqualTo(mapper.readTree("[{\"date\":\"2026-10-02\",\"count\":9876543210}]"))
        verify(statistics).dailyAnimals(DAY, DAY.minusDays(1))
    }

    @Test
    fun givenNoRecordedDays__whenGettingAdminTrendAndDaily__thenKeepEmptySeriesAndZeroPreviousOnly() {
        `when`(statistics.intakeTrend(DAY, DAY)).thenReturn(IntakeTrend(DAY, DAY, listOf(), 0))
        `when`(statistics.dailyAnimals(DAY, DAY)).thenReturn(listOf())

        assertThat(response("/api/v1/admin/stats/intake-trend?startDate=2026-10-02&endDate=2026-10-02").path("daily"))
            .isEqualTo(mapper.createArrayNode())
        assertThat(response("/api/v1/admin/stats/daily-animals?startDate=2026-10-02&endDate=2026-10-02"))
            .isEqualTo(mapper.createArrayNode())
    }

    @ParameterizedTest
    @MethodSource("malformedDates")
    fun givenMalformedDate__whenBinding__thenKeepAdminEmpty400AndPublicGeneric500(
        route: String,
        expected: Int,
        empty: Boolean,
    ) {
        val result = mvc.perform(get(route)).andExpect(status().`is`(expected)).andReturn().response

        if (empty) {
            assertThat(result.contentAsString).isEmpty()
        } else {
            assertThat(mapper.readTree(result.getContentAsString(StandardCharsets.UTF_8)))
                .isEqualTo(
                    mapper.createObjectNode().put("code", 500).put("message", "서버 내부 오류가 발생했습니다.").putNull("data"),
                )
        }
        verifyNoInteractions(statistics)
    }

    @ParameterizedTest
    @MethodSource("missingAdminDates")
    fun givenMissingRequiredAdminDate__whenBinding__thenKeepGlobal400Envelope(route: String, missing: String) {
        val result = mvc.perform(get(route)).andExpect(status().isBadRequest()).andReturn().response

        assertThat(mapper.readTree(result.getContentAsString(StandardCharsets.UTF_8)))
            .isEqualTo(
                mapper.createObjectNode()
                    .put("code", 400)
                    .put("message", "필수 요청 파라미터 '" + missing + "'(이)가 누락되었습니다.")
                    .putNull("data"),
            )
        verifyNoInteractions(statistics)
    }

    @Test
    fun givenInvalidTrendRange__whenGetting__thenKeepSource400Message() {
        val message = "오늘까지 최대 366일의 올바른 조회 기간이 필요합니다."
        `when`(statistics.intakeTrend(DAY, DAY)).thenThrow(IllegalArgumentException(message))

        val result = mvc.perform(get("/api/v1/admin/stats/intake-trend?startDate=2026-10-02&endDate=2026-10-02"))
            .andExpect(status().isBadRequest()).andReturn().response

        assertThat(mapper.readTree(result.getContentAsString(StandardCharsets.UTF_8)))
            .isEqualTo(mapper.createObjectNode().put("code", 400).put("message", message).putNull("data"))
    }

    @ParameterizedTest
    @MethodSource("storageFailures")
    fun givenStorageFailure__whenGettingToday__thenKeepOriginal500AndNeverDisguiseItAsZero(
        failure: RuntimeException,
        message: String,
    ) {
        `when`(statistics.today()).thenThrow(failure)

        val result = mvc.perform(get("/api/v1/animals/stats/today"))
            .andExpect(status().isInternalServerError()).andReturn().response

        assertThat(mapper.readTree(result.getContentAsString(StandardCharsets.UTF_8)))
            .isEqualTo(mapper.createObjectNode().put("code", 500).put("message", message).putNull("data"))
    }

    private fun response(route: String): JsonNode {
        val result = mvc.perform(get(route)).andExpect(status().isOk()).andReturn().response
        return mapper.readTree(result.getContentAsString(StandardCharsets.UTF_8))
    }

    companion object {
        private val DAY: LocalDate = LocalDate.of(2026, 10, 2)

        @JvmStatic
        fun malformedDates(): Stream<Arguments> = Stream.of(
            Arguments.of("/api/v1/admin/stats/intake-trend?startDate=2026-02-30&endDate=2026-10-02", 400, true),
            Arguments.of("/api/v1/admin/stats/daily-animals?startDate=not-a-date&endDate=2026-10-02", 400, true),
            Arguments.of("/api/v1/animals/stats/status?startDate=not-a-date", 500, false),
            Arguments.of("/api/v1/animals/stats/regional?endDate=2026-02-30", 500, false),
        )

        @JvmStatic
        fun missingAdminDates(): Stream<Arguments> = Stream.of(
            Arguments.of("/api/v1/admin/stats/intake-trend?endDate=2026-10-02", "startDate"),
            Arguments.of("/api/v1/admin/stats/daily-animals?startDate=2026-10-02", "endDate"),
        )

        @JvmStatic
        fun storageFailures(): Stream<Arguments> = Stream.of(
            Arguments.of(DataAccessResourceFailureException("synthetic private database information"), "데이터베이스 오류가 발생했습니다."),
            Arguments.of(IllegalStateException("synthetic private failure"), "서버 내부 오류가 발생했습니다."),
        )
    }
}
