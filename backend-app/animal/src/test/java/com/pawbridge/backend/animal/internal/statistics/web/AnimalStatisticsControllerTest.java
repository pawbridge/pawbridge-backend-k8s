package com.pawbridge.backend.animal.internal.statistics.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pawbridge.backend.animal.internal.statistics.application.AnimalStatistics;
import com.pawbridge.backend.animal.internal.statistics.application.AnimalStatistics.*;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AnimalStatisticsControllerTest {
    private final AnimalStatistics statistics = mock(AnimalStatistics.class);
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AnimalStatisticsController(statistics), new AdminAnimalStatisticsController(statistics))
            .setControllerAdvice(new AnimalStatisticsExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
    private static final LocalDate DAY = LocalDate.of(2026, 10, 2);

    @Test
    void givenTodayCounts__whenGettingPublicToday__thenKeepOriginalThreeFieldResponseAndLongCounts() throws Exception {
        when(statistics.today()).thenReturn(new Today(DAY, 9876543210L, 5L));
        assertThat(response("/api/v1/animals/stats/today")).isEqualTo(mapper.readTree("{\"date\":\"2026-10-02\",\"rescuedToday\":9876543210,\"adoptedToday\":5}"));
    }

    @Test
    void givenCurrentStatuses__whenGettingPublicStatus__thenKeepCodeCountLabelAndReturnedOrder() throws Exception {
        when(statistics.status(null, null)).thenReturn(List.of(new StatusCount(Status.PROTECT, 8L), new StatusCount(Status.ADOPTED, 2L)));
        assertThat(response("/api/v1/animals/stats/status")).isEqualTo(mapper.readTree("""
                [{"status":"PROTECT","count":8,"label":"보호중"},{"status":"ADOPTED","count":2,"label":"종료(입양)"}]
                """));
        verify(statistics).status(null, null);
    }

    @Test
    void givenExplicitOrMissingPublicBounds__whenBinding__thenPassRawDatesWithoutControllerDefaults() throws Exception {
        when(statistics.status(any(), any())).thenReturn(List.of());
        when(statistics.regional(any(), any())).thenReturn(List.of(new RegionalCount("서울", 9876543210L)));
        response("/api/v1/animals/stats/status?startDate=2026-10-02&endDate=2026-10-01");
        verify(statistics).status(DAY, DAY.minusDays(1));
        assertThat(response("/api/v1/animals/stats/regional?endDate=2026-10-02"))
                .isEqualTo(mapper.readTree("[{\"region\":\"서울\",\"count\":9876543210}]"));
        verify(statistics).regional(null, DAY);
    }

    @Test
    void givenAdminIntakeTrend__whenGetting__thenKeepOriginalDailyAndPreviousDayContractWithoutFilledDates() throws Exception {
        when(statistics.intakeTrend(DAY.minusDays(2), DAY)).thenReturn(new IntakeTrend(DAY.minusDays(2), DAY,
                List.of(new DailyCount(DAY.minusDays(2), 2L), new DailyCount(DAY, 4L)), 7));
        assertThat(response("/api/v1/admin/stats/intake-trend?startDate=2026-09-30&endDate=2026-10-02"))
                .isEqualTo(mapper.readTree("""
                {"startDate":"2026-09-30","endDate":"2026-10-02","daily":[{"date":"2026-09-30","count":2},{"date":"2026-10-02","count":4}],"previousDayCount":7}
                """));
    }

    @Test
    void givenDailyRegistrationCounts__whenGettingAdminDaily__thenKeepArrayAndRawBoundariesWithoutIntakeValidation() throws Exception {
        when(statistics.dailyAnimals(DAY, DAY.minusDays(1))).thenReturn(List.of(new DailyCount(DAY, 9876543210L)));
        assertThat(response("/api/v1/admin/stats/daily-animals?startDate=2026-10-02&endDate=2026-10-01"))
                .isEqualTo(mapper.readTree("[{\"date\":\"2026-10-02\",\"count\":9876543210}]"));
        verify(statistics).dailyAnimals(DAY, DAY.minusDays(1));
    }

    @Test
    void givenNoRecordedDays__whenGettingAdminTrendAndDaily__thenKeepEmptySeriesAndZeroPreviousOnly() throws Exception {
        when(statistics.intakeTrend(DAY, DAY)).thenReturn(new IntakeTrend(DAY, DAY, List.of(), 0));
        when(statistics.dailyAnimals(DAY, DAY)).thenReturn(List.of());
        assertThat(response("/api/v1/admin/stats/intake-trend?startDate=2026-10-02&endDate=2026-10-02").path("daily")).isEqualTo(mapper.createArrayNode());
        assertThat(response("/api/v1/admin/stats/daily-animals?startDate=2026-10-02&endDate=2026-10-02")).isEqualTo(mapper.createArrayNode());
    }

    @ParameterizedTest
    @MethodSource("malformedDates")
    void givenMalformedDate__whenBinding__thenKeepAdminEmpty400AndPublicGeneric500(String route, int expected, boolean empty) throws Exception {
        var result = mvc.perform(get(route)).andExpect(status().is(expected)).andReturn().getResponse();
        if (empty) assertThat(result.getContentAsString()).isEmpty();
        else assertThat(mapper.readTree(result.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)))
                .isEqualTo(mapper.createObjectNode().put("code", 500).put("message", "서버 내부 오류가 발생했습니다.").putNull("data"));
        verifyNoInteractions(statistics);
    }
    static Stream<Arguments> malformedDates() {
        return Stream.of(Arguments.of("/api/v1/admin/stats/intake-trend?startDate=2026-02-30&endDate=2026-10-02", 400, true),
                Arguments.of("/api/v1/admin/stats/daily-animals?startDate=not-a-date&endDate=2026-10-02", 400, true),
                Arguments.of("/api/v1/animals/stats/status?startDate=not-a-date", 500, false),
                Arguments.of("/api/v1/animals/stats/regional?endDate=2026-02-30", 500, false));
    }

    @ParameterizedTest
    @MethodSource("missingAdminDates")
    void givenMissingRequiredAdminDate__whenBinding__thenKeepGlobal400Envelope(String route, String missing) throws Exception {
        var result = mvc.perform(get(route)).andExpect(status().isBadRequest()).andReturn().getResponse();
        assertThat(mapper.readTree(result.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)))
                .isEqualTo(mapper.createObjectNode().put("code", 400).put("message", "필수 요청 파라미터 '" + missing + "'(이)가 누락되었습니다.").putNull("data"));
        verifyNoInteractions(statistics);
    }
    static Stream<Arguments> missingAdminDates() {
        return Stream.of(Arguments.of("/api/v1/admin/stats/intake-trend?endDate=2026-10-02", "startDate"),
                Arguments.of("/api/v1/admin/stats/daily-animals?startDate=2026-10-02", "endDate"));
    }

    @Test
    void givenInvalidTrendRange__whenGetting__thenKeepSource400Message() throws Exception {
        String message = "오늘까지 최대 366일의 올바른 조회 기간이 필요합니다.";
        when(statistics.intakeTrend(DAY, DAY)).thenThrow(new IllegalArgumentException(message));
        var result = mvc.perform(get("/api/v1/admin/stats/intake-trend?startDate=2026-10-02&endDate=2026-10-02"))
                .andExpect(status().isBadRequest()).andReturn().getResponse();
        assertThat(mapper.readTree(result.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)))
                .isEqualTo(mapper.createObjectNode().put("code", 400).put("message", message).putNull("data"));
    }

    @ParameterizedTest
    @MethodSource("storageFailures")
    void givenStorageFailure__whenGettingToday__thenKeepOriginal500AndNeverDisguiseItAsZero(RuntimeException failure, String message) throws Exception {
        when(statistics.today()).thenThrow(failure);
        var result = mvc.perform(get("/api/v1/animals/stats/today")).andExpect(status().isInternalServerError()).andReturn().getResponse();
        assertThat(mapper.readTree(result.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)))
                .isEqualTo(mapper.createObjectNode().put("code", 500).put("message", message).putNull("data"));
    }
    static Stream<Arguments> storageFailures() {
        return Stream.of(Arguments.of(new DataAccessResourceFailureException("synthetic private database information"), "데이터베이스 오류가 발생했습니다."),
                Arguments.of(new IllegalStateException("synthetic private failure"), "서버 내부 오류가 발생했습니다."));
    }

    private JsonNode response(String route) throws Exception {
        var result = mvc.perform(get(route)).andExpect(status().isOk()).andReturn().getResponse();
        return mapper.readTree(result.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }
}
