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
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
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
    private val statistics: AnimalStatistics = mockk<AnimalStatistics>()
    private val mapper: ObjectMapper = ObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    private val mvc: MockMvc = MockMvcBuilders
        .standaloneSetup(AnimalStatisticsController(statistics), AdminAnimalStatisticsController(statistics))
        .setControllerAdvice(AnimalStatisticsExceptionHandler())
        .setMessageConverters(MappingJackson2HttpMessageConverter(mapper))
        .build()

    @Test
    fun `오늘 집계가 있을 때__공개 오늘 통계를 조회하면__원래의 세 필드 응답과 Long 건수를 유지한다`() {
        // given
        every { statistics.today() } returns Today(DAY, 9876543210L, 5L)

        // when
        val body = response("/api/v1/animals/stats/today")

        // then
        assertThat(body).isEqualTo(
            mapper.readTree("{\"date\":\"2026-10-02\",\"rescuedToday\":9876543210,\"adoptedToday\":5}"),
        )
    }

    @Test
    fun `현재 상태별 집계가 있을 때__공개 상태 통계를 조회하면__코드와 건수와 라벨과 반환 순서를 유지한다`() {
        // given
        every { statistics.status(any(), any()) } returns
            listOf(StatusCount(Status.PROTECT, 8L), StatusCount(Status.ADOPTED, 2L))

        // when
        val body = response("/api/v1/animals/stats/status")

        // then
        assertThat(body).isEqualTo(
            mapper.readTree(
                """
                [{"status":"PROTECT","count":8,"label":"보호중"},{"status":"ADOPTED","count":2,"label":"종료(입양)"}]
                """.trimIndent(),
            ),
        )
        verify(exactly = 1) { statistics.status(null, null) }
    }

    @Test
    fun `공개 기간 경계가 명시되거나 빠졌을 때__바인딩하면__컨트롤러 기본값 없이 날짜를 그대로 넘긴다`() {
        // given
        every { statistics.status(any(), any()) } returns listOf()
        every { statistics.regional(any(), any()) } returns listOf(RegionalCount("서울", 9876543210L))

        // when
        response("/api/v1/animals/stats/status?startDate=2026-10-02&endDate=2026-10-01")
        val regional = response("/api/v1/animals/stats/regional?endDate=2026-10-02")

        // then
        verify(exactly = 1) { statistics.status(DAY, DAY.minusDays(1)) }
        assertThat(regional).isEqualTo(mapper.readTree("[{\"region\":\"서울\",\"count\":9876543210}]"))
        verify(exactly = 1) { statistics.regional(null, DAY) }
    }

    @Test
    fun `관리자 유입 추이가 있을 때__조회하면__채움 없이 원래의 일별 값과 전일 건수 계약을 유지한다`() {
        // given
        every { statistics.intakeTrend(DAY.minusDays(2), DAY) } returns IntakeTrend(
            DAY.minusDays(2),
            DAY,
            listOf(DailyCount(DAY.minusDays(2), 2L), DailyCount(DAY, 4L)),
            7,
        )

        // when
        val body = response("/api/v1/admin/stats/intake-trend?startDate=2026-09-30&endDate=2026-10-02")

        // then
        assertThat(body).isEqualTo(
            mapper.readTree(
                """
                {"startDate":"2026-09-30","endDate":"2026-10-02","daily":[{"date":"2026-09-30","count":2},{"date":"2026-10-02","count":4}],"previousDayCount":7}
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `일별 등록 건수가 있을 때__관리자 일별 통계를 조회하면__유입 검증 없이 배열과 날짜 경계를 그대로 유지한다`() {
        // given
        every { statistics.dailyAnimals(DAY, DAY.minusDays(1)) } returns listOf(DailyCount(DAY, 9876543210L))

        // when
        val body = response("/api/v1/admin/stats/daily-animals?startDate=2026-10-02&endDate=2026-10-01")

        // then
        assertThat(body).isEqualTo(mapper.readTree("[{\"date\":\"2026-10-02\",\"count\":9876543210}]"))
        verify(exactly = 1) { statistics.dailyAnimals(DAY, DAY.minusDays(1)) }
    }

    @Test
    fun `기록된 날이 없을 때__관리자 추이와 일별 통계를 조회하면__빈 시리즈와 전일 0만 유지한다`() {
        // given
        every { statistics.intakeTrend(DAY, DAY) } returns IntakeTrend(DAY, DAY, listOf(), 0)
        every { statistics.dailyAnimals(DAY, DAY) } returns listOf()

        // when
        val trend = response("/api/v1/admin/stats/intake-trend?startDate=2026-10-02&endDate=2026-10-02")
        val daily = response("/api/v1/admin/stats/daily-animals?startDate=2026-10-02&endDate=2026-10-02")

        // then
        assertThat(trend.path("daily")).isEqualTo(mapper.createArrayNode())
        assertThat(daily).isEqualTo(mapper.createArrayNode())
    }

    @ParameterizedTest
    @MethodSource("malformedDates")
    fun `잘못된 형식의 날짜가 주어졌을 때__바인딩하면__관리자는 빈 400을 공개는 일반 500을 유지한다`(
        route: String,
        expected: Int,
        empty: Boolean,
    ) {
        // given

        // when
        val result = mvc.perform(get(route)).andExpect(status().`is`(expected)).andReturn().response

        // then
        if (empty) {
            assertThat(result.contentAsString).isEmpty()
        } else {
            assertThat(mapper.readTree(result.getContentAsString(StandardCharsets.UTF_8)))
                .isEqualTo(
                    mapper.createObjectNode().put("code", 500).put("message", "서버 내부 오류가 발생했습니다.").putNull("data"),
                )
        }
        confirmVerified(statistics)
    }

    @ParameterizedTest
    @MethodSource("missingAdminDates")
    fun `필수 관리자 날짜가 빠졌을 때__바인딩하면__전역 400 응답 형식을 유지한다`(route: String, missing: String) {
        // given

        // when
        val result = mvc.perform(get(route)).andExpect(status().isBadRequest()).andReturn().response

        // then
        assertThat(mapper.readTree(result.getContentAsString(StandardCharsets.UTF_8)))
            .isEqualTo(
                mapper.createObjectNode()
                    .put("code", 400)
                    .put("message", "필수 요청 파라미터 '" + missing + "'(이)가 누락되었습니다.")
                    .putNull("data"),
            )
        confirmVerified(statistics)
    }

    @Test
    fun `추이 조회 기간이 올바르지 않을 때__조회하면__원래의 400 메시지를 유지한다`() {
        // given
        val message = "오늘까지 최대 366일의 올바른 조회 기간이 필요합니다."

        every { statistics.intakeTrend(DAY, DAY) } throws IllegalArgumentException(message)

        // when
        val result = mvc.perform(get("/api/v1/admin/stats/intake-trend?startDate=2026-10-02&endDate=2026-10-02"))
            .andExpect(status().isBadRequest()).andReturn().response

        // then
        assertThat(mapper.readTree(result.getContentAsString(StandardCharsets.UTF_8)))
            .isEqualTo(mapper.createObjectNode().put("code", 400).put("message", message).putNull("data"))
    }

    @ParameterizedTest
    @MethodSource("storageFailures")
    fun `저장소 오류가 났을 때__오늘 통계를 조회하면__원래의 500을 유지하고 0으로 위장하지 않는다`(
        failure: RuntimeException,
        message: String,
    ) {
        // given
        every { statistics.today() } throws failure

        // when
        val result = mvc.perform(get("/api/v1/animals/stats/today"))
            .andExpect(status().isInternalServerError()).andReturn().response

        // then
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
