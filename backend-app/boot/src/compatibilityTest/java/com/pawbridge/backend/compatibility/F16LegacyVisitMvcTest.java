package com.pawbridge.backend.compatibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.community.internal.visit.application.ReadTodayVisits;
import com.pawbridge.backend.community.internal.visit.application.VisitCountReader;
import com.pawbridge.backend.community.internal.visit.web.VisitTodayController;
import com.pawbridge.backend.community.internal.visit.web.VisitTodayExceptionHandler;
import com.pawbridge.communityservice.exception.common.GlobalExceptionRestAdvice;
import com.pawbridge.communityservice.visits.VisitController;
import com.pawbridge.communityservice.visits.VisitCookie;
import com.pawbridge.communityservice.visits.VisitExceptionAdvice;
import com.pawbridge.communityservice.visits.VisitModels;
import com.pawbridge.communityservice.visits.VisitService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Actual original controller and advice, once in UTC; no original configuration, DB or security bootstrap. */
class F16LegacyVisitMvcTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2031-04-17T15:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate DAY = LocalDate.parse("2031-04-18");

    @ParameterizedTest @ValueSource(longs = {0, Long.MAX_VALUE})
    void givenAnAggregate__whenTheActualOriginalAndCandidateMvcSerialize__thenExactEnvelopeDateLongAndNoStoreMatch(long count) throws Exception {
        VisitService original = mock(VisitService.class); when(original.today()).thenReturn(new VisitModels.Today(DAY, count));
        VisitCountReader reader = mock(VisitCountReader.class); when(reader.count(DAY)).thenReturn(count);
        try (var context = new AnnotationConfigApplicationContext(F16VisitFixture.BootJackson.class)) {
            ObjectMapper mapper = context.getBean(ObjectMapper.class);
            JsonNode body = compare(original(original, mapper), candidate(reader, mapper), mapper, 200, "no-store");
            assertThat(body.get("code").intValue()).isEqualTo(200);
            assertThat(body.get("message").asText()).isEqualTo("요청이 성공적으로 처리되었습니다.");
            assertThat(body.get("data").get("date").asText()).isEqualTo(DAY.toString());
            assertThat(body.get("data").get("count").isIntegralNumber()).isTrue();
            assertThat(body.get("data").get("count").longValue()).isEqualTo(count);
            assertThat(body.size()).isEqualTo(3); assertThat(body.get("data").size()).isEqualTo(2);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void givenDaoOrGeneralRuntimeFailure__whenTheActualOriginalAndCandidateAdviceHandleIt__thenOnlyDaoGets503AndExplicitNoStore(boolean dao) throws Exception {
        RuntimeException failure = dao ? new DataAccessResourceFailureException("synthetic fixture failure") : new IllegalStateException("synthetic fixture failure");
        VisitService original = mock(VisitService.class); when(original.today()).thenThrow(failure);
        VisitCountReader reader = mock(VisitCountReader.class); when(reader.count(DAY)).thenThrow(failure);
        try (var context = new AnnotationConfigApplicationContext(F16VisitFixture.BootJackson.class)) {
            ObjectMapper mapper = context.getBean(ObjectMapper.class);
            JsonNode body = compare(original(original, mapper), candidate(reader, mapper), mapper, dao ? 503 : 500, dao ? "no-store" : null);
            assertThat(body.get("message").asText()).isEqualTo(dao ? "방문 통계를 불러올 수 없습니다." : "서버 에러!");
            assertThat(body.get("data").isNull()).isTrue(); assertThat(body.size()).isEqualTo(3);
        }
    }

    private MockMvc original(VisitService service, ObjectMapper mapper) {
        return MockMvcBuilders.standaloneSetup(new VisitController(service,
                        new VisitCookie(Base64.getEncoder().encodeToString(new byte[32])), CLOCK, "https://fixture.invalid"))
                .setControllerAdvice(new VisitExceptionAdvice(), new GlobalExceptionRestAdvice())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
    }
    private MockMvc candidate(VisitCountReader reader, ObjectMapper mapper) {
        return MockMvcBuilders.standaloneSetup(new VisitTodayController(new ReadTodayVisits(reader, CLOCK)))
                .setControllerAdvice(new VisitTodayExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
    }
    private JsonNode compare(MockMvc original, MockMvc candidate, ObjectMapper mapper, int status, String cache) throws Exception {
        MvcResult before = original.perform(get("/api/v1/visits/today")).andReturn();
        MvcResult after = candidate.perform(get("/api/v1/visits/today")).andReturn();
        assertThat(before.getResponse().getStatus()).isEqualTo(status); assertThat(after.getResponse().getStatus()).isEqualTo(status);
        assertThat(before.getResponse().getHeader("Cache-Control")).isEqualTo(cache); assertThat(after.getResponse().getHeader("Cache-Control")).isEqualTo(cache);
        assertThat(before.getResponse().getHeaders("Set-Cookie")).isEmpty(); assertThat(after.getResponse().getHeaders("Set-Cookie")).isEmpty();
        JsonNode expected = mapper.readTree(before.getResponse().getContentAsByteArray());
        JsonNode actual = mapper.readTree(after.getResponse().getContentAsByteArray());
        assertThat(actual).isEqualTo(expected); return actual;
    }
}
