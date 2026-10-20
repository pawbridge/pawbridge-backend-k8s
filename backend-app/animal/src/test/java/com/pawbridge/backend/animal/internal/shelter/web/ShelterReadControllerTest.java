package com.pawbridge.backend.animal.internal.shelter.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pawbridge.backend.animal.internal.shelter.application.ShelterNotFoundException;
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads;
import com.pawbridge.backend.animal.internal.shelter.application.ShelterReads.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ShelterReadControllerTest {
    private final ShelterReads shelters = mock(ShelterReads.class);
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ShelterReadController(shelters), new ShelterDiscoveryReadController(shelters))
            .setControllerAdvice(new ShelterReadExceptionHandler()).setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();

    @Test
    void givenOrdinaryList__whenListing__thenKeepDefaultPageSortFullPageMetadataAndSevenSummaryFields() throws Exception {
        LocalDateTime created = LocalDateTime.of(2026, 9, 29, 13, 4, 5);
        when(shelters.search(any(), any())).thenReturn(new Page<>(List.of(new Summary(4L, "reg-4", "센터", "02", "서울", "시청", created)), 1));
        JsonNode actual = response("/api/v1/shelters");
        verify(shelters).search(new Search(null, null, null, null), new PageRequest(0, 20, List.of(new Order("name", true, false))));
        assertThat(actual.properties()).extracting(java.util.Map.Entry::getKey).containsExactlyInAnyOrder("content", "pageable", "last", "totalPages", "totalElements",
                "size", "number", "sort", "first", "numberOfElements", "empty");
        assertThat(actual.path("content").get(0)).isEqualTo(mapper.readTree("""
                {"id":4,"careRegNo":"reg-4","name":"센터","phone":"02","address":"서울","organizationName":"시청","createdAt":"2026-09-29T13:04:05"}
                """));
        assertThat(actual.path("size").asInt()).isEqualTo(20);
        assertThat(actual.path("totalElements").asLong()).isOne();
        assertThat(actual.path("pageable").path("pageSize").asInt()).isEqualTo(20);
        assertThat(actual.path("sort").path("sorted").asBoolean()).isTrue();
        assertThat(actual.path("first").asBoolean()).isTrue();
        assertThat(actual.path("last").asBoolean()).isTrue();
    }

    @Test
    void givenAllFiltersAndCustomPageSort__whenListing__thenForwardRawSearchTextAndAllOrdersWithoutCombiningFilters() throws Exception {
        when(shelters.search(any(), any())).thenReturn(new Page<>(List.of(), 0));
        mvc.perform(get("/api/v1/shelters").param("name", "name").param("keyword", " 센터 ").param("address", " 서울 ")
                .param("organizationName", "org").param("page", "2").param("size", "12").param("sort", "name,desc,ignorecase", "createdAt,asc"))
                .andExpect(status().isOk());
        verify(shelters).search(new Search("name", " 서울 ", "org", " 센터 "), new PageRequest(2, 12,
                List.of(new Order("name", false, true), new Order("createdAt", true, false))));
    }

    @Test
    void givenDetailAndRegistrationLookup__whenReading__thenReturnAll14FieldsAndOriginalPublicInformation() throws Exception {
        LocalDateTime created = LocalDateTime.of(2026, 9, 29, 13, 4, 5), updated = created.plusDays(1);
        var publicInformation = new PublicInformation("APMS_SHELTER_INFO", "02-public", 37.5, 127.1, "09", "18", "10", "16", "월", "2026-09-29", updated);
        var detail = new Details(4L, "reg-4", "센터", "02", "서울", "대표", "시청", "sample@example.test", "소개", "절차", "09-18", created, updated, publicInformation);
        when(shelters.findById(4)).thenReturn(detail);
        when(shelters.findByCareRegNo("reg-4")).thenReturn(detail);
        JsonNode expected = mapper.readTree("""
                {"id":4,"careRegNo":"reg-4","name":"센터","phone":"02","address":"서울","ownerName":"대표","organizationName":"시청",
                "email":"sample@example.test","introduction":"소개","adoptionProcedure":"절차","operatingHours":"09-18",
                "createdAt":"2026-09-29T13:04:05","updatedAt":"2026-09-30T13:04:05",
                "publicInformation":{"source":"APMS_SHELTER_INFO","phone":"02-public","latitude":37.5,"longitude":127.1,
                "weekdayOpen":"09","weekdayClose":"18","weekendOpen":"10","weekendClose":"16","closedDays":"월",
                "sourceUpdatedDate":"2026-09-29","collectedAt":"2026-09-30T13:04:05"}}
                """);
        assertThat(response("/api/v1/shelters/4")).isEqualTo(expected);
        assertThat(response("/api/v1/shelters/by-care-reg-no/reg-4")).isEqualTo(expected);
    }

    @Test
    void givenNullableDetailFieldsAndScalarCount__whenReading__thenOmitNullsAndReturnLongWithoutEnvelope() throws Exception {
        when(shelters.findById(4)).thenReturn(new Details(4L, "reg-4", "센터", null, null, null, null, null, null, null, null, null, null, null));
        assertThat(response("/api/v1/shelters/4")).isEqualTo(mapper.readTree("{\"id\":4,\"careRegNo\":\"reg-4\",\"name\":\"센터\"}"));
        when(shelters.count()).thenReturn(9876543210L);
        assertThat(response("/api/v1/shelters/count").asLong()).isEqualTo(9876543210L);
    }

    @Test
    void givenDiscoveryDatesFiltersAndPage__whenDiscovering__thenBindOriginalInputsAndIncludeNullablePreviewFields() throws Exception {
        when(shelters.discover(anyString(), anyString(), any(), any(), anyInt(), anyInt())).thenReturn(new Page<>(List.of(
                new Discovery(4, "reg", "센터", null, null, 1, List.of(new Preview(40, null, "DOG", "UNKNOWN", null, null, LocalDate.of(2026, 9, 29))))), 99));
        var result = mvc.perform(get("/api/v1/shelters/discovery").param("intakeFrom", "2026-09-01").param("intakeTo", "2026-09-30")
                .param("keyword", "보호소").param("address", "서울").param("page", "2")).andExpect(status().isOk()).andReturn().getResponse();
        verify(shelters).discover("보호소", "서울", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), 2, 12);
        JsonNode actual = mapper.readTree(result.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(actual.path("content").get(0)).isEqualTo(mapper.readTree("""
                {"id":4,"careRegNo":"reg","name":"센터","address":null,"phone":null,"protectedCount":1,
                "animals":[{"id":40,"breed":null,"species":"DOG","gender":"UNKNOWN","birthYear":null,"imageUrl":null,"happenDate":"2026-09-29"}]}
                """));
        assertThat(actual.path("number").asInt()).isEqualTo(2);
        assertThat(actual.path("size").asInt()).isEqualTo(12);
        assertThat(actual.path("totalElements").asLong()).isEqualTo(99);
        assertThat(actual.path("sort").path("unsorted").asBoolean()).isTrue();
    }

    @Test
    void givenNoIntakeDates__whenDiscovering__thenDefaultToInclusiveThirtyDaysEndingTodayInSeoul() throws Exception {
        when(shelters.discover(anyString(), anyString(), any(), any(), anyInt(), anyInt())).thenReturn(new Page<>(List.of(), 0));
        response("/api/v1/shelters/discovery");
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        verify(shelters).discover("", "", today.minusDays(29), today, 0, 12);
    }

    @Test
    void givenOnlyOneIntakeBound__whenDiscovering__thenForwardNullOtherBoundForOriginalRangeValidation() throws Exception {
        when(shelters.discover(anyString(), anyString(), any(), any(), anyInt(), anyInt()))
                .thenThrow(new IllegalArgumentException("접수일은 시작일과 종료일을 함께 지정하고 최대 366일 이내로 선택해주세요."));
        mvc.perform(get("/api/v1/shelters/discovery").param("intakeFrom", "2026-09-01")).andExpect(status().isBadRequest());
        verify(shelters).discover("", "", LocalDate.of(2026, 9, 1), null, 0, 12);
    }

    @ParameterizedTest
    @MethodSource("malformed")
    void givenMalformedDiscoveryParameter__whenBinding__thenKeepLocalEmpty400WithoutCallingApplication(String route, String parameter, String value) throws Exception {
        var request = get(route);
        if (parameter != null) request.param(parameter, value);
        var result = mvc.perform(request).andExpect(status().isBadRequest()).andReturn().getResponse();
        assertThat(result.getContentAsString()).isEmpty();
        verifyNoInteractions(shelters);
    }
    static Stream<Arguments> malformed() {
        return Stream.of(Arguments.of("/api/v1/shelters/discovery", "intakeFrom", "not-a-date"),
                Arguments.of("/api/v1/shelters/discovery", "page", "not-a-number"),
                Arguments.of("/api/v1/shelters/invalid/observations?from=2026-09-01&to=2026-09-30", null, null));
    }

    @Test
    void givenRecordedObservationsOrEmptySeries__whenReading__thenKeepDateOffsetAndAbsentDaysWithoutZeroFill() throws Exception {
        var observed = new Observation(LocalDate.of(2026, 9, 3), OffsetDateTime.parse("2026-09-03T10:11:12+09:00"), 8);
        when(shelters.observations(4, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30))).thenReturn(List.of(observed));
        String route = "/api/v1/shelters/4/observations?from=2026-09-01&to=2026-09-30";
        assertThat(response(route)).isEqualTo(mapper.readTree("[{\"date\":\"2026-09-03\",\"observedAt\":\"2026-09-03T10:11:12+09:00\",\"protectedCount\":8}]"));
        when(shelters.observations(4, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30))).thenReturn(List.of());
        assertThat(response(route)).isEqualTo(mapper.createArrayNode());
    }

    @Test
    void givenMissingObservationDateParameter__whenBinding__thenKeepGlobal400Envelope() throws Exception {
        var result = mvc.perform(get("/api/v1/shelters/4/observations").param("to", "2026-09-30"))
                .andExpect(status().isBadRequest()).andReturn().getResponse();
        assertThat(mapper.readTree(result.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)))
                .isEqualTo(mapper.createObjectNode().put("code", 400).put("message", "필수 요청 파라미터 'from'(이)가 누락되었습니다.").putNull("data"));
        verifyNoInteractions(shelters);
    }

    @ParameterizedTest
    @MethodSource("failures")
    void givenReadFailure__whenGetting__thenKeepSourceStatusAndSafeEnvelope(RuntimeException failure, boolean observations, int code, String message) throws Exception {
        String route;
        if (observations) {
            when(shelters.observations(anyLong(), any(), any())).thenThrow(failure);
            route = "/api/v1/shelters/4/observations?from=2026-09-01&to=2026-09-30";
        } else {
            when(shelters.findById(4)).thenThrow(failure);
            route = "/api/v1/shelters/4";
        }
        var result = mvc.perform(get(route)).andExpect(status().is(code)).andReturn().getResponse();
        assertThat(mapper.readTree(result.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)))
                .isEqualTo(mapper.createObjectNode().put("code", code).put("message", message).putNull("data"));
    }
    static Stream<Arguments> failures() {
        return Stream.of(Arguments.of(new IllegalStateException("Shelter not found: 4"), false, 500, "서버 내부 오류가 발생했습니다."),
                Arguments.of(new ShelterNotFoundException(), true, 404, "보호소를 찾을 수 없습니다"),
                Arguments.of(new IllegalArgumentException("invalid range"), true, 400, "invalid range"),
                Arguments.of(new DataAccessResourceFailureException("synthetic private detail"), false, 500, "데이터베이스 오류가 발생했습니다."));
    }

    @Test
    void givenMalformedOrdinaryDetailId__whenBinding__thenKeepGeneric500AndNoApplicationCall() throws Exception {
        mvc.perform(get("/api/v1/shelters/invalid")).andExpect(status().isInternalServerError());
        verifyNoInteractions(shelters);
    }

    private JsonNode response(String route) throws Exception {
        var result = mvc.perform(get(route)).andExpect(status().isOk()).andReturn().getResponse();
        return mapper.readTree(result.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }
}
