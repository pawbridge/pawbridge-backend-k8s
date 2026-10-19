package com.pawbridge.backend.compatibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.user.internal.statistics.application.TotalUserCountQuery;
import com.pawbridge.backend.user.internal.statistics.web.TotalUserCountController;
import com.pawbridge.backend.user.internal.statistics.web.TotalUserCountExceptionHandler;
import com.pawbridge.userservice.controller.F19LegacyTotalUserCountController;
import com.pawbridge.userservice.exception.common.GlobalExceptionRestAdvice;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** F19 MVC comparison: actual selected controller/advice; callback is test wiring, not the original User service. */
class F19LegacyTotalUserCountMvcTest {
    @ParameterizedTest @ValueSource(longs = {0, Long.MAX_VALUE})
    void givenIntegralTotalCounts__whenBothMvcSerialize__thenExactCommonEnvelopeAndUnwrappedLongAgree(long count) throws Exception {
        var query = mock(TotalUserCountQuery.class); when(query.count()).thenReturn(count);
        try (var context = new AnnotationConfigApplicationContext(F16VisitFixture.BootJackson.class)) {
            ObjectMapper mapper = context.getBean(ObjectMapper.class);
            var before = original(new F19LegacyTotalUserCountController(() -> count), mapper);
            JsonNode result = compare(before, candidate(query, mapper), mapper, 200);
            assertThat(result.size()).isEqualTo(3); assertThat(result.get("code").intValue()).isEqualTo(200);
            assertThat(result.get("message").asText()).isEqualTo("요청이 성공적으로 처리되었습니다.");
            assertThat(result.get("data").isIntegralNumber()).isTrue(); assertThat(result.get("data").longValue()).isEqualTo(count);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void givenDaoOrGeneralRuntimeFailure__whenBothScopedHttpBoundariesRespond__thenReturnSafe500InsteadOfZeroOr503(boolean dao) throws Exception {
        RuntimeException failure = dao ? new DataAccessResourceFailureException("synthetic private SQL") : new IllegalStateException("synthetic private runtime");
        var query = mock(TotalUserCountQuery.class); when(query.count()).thenThrow(failure);
        try (var context = new AnnotationConfigApplicationContext(F16VisitFixture.BootJackson.class)) {
            ObjectMapper mapper = context.getBean(ObjectMapper.class);
            var before = original(new F19LegacyTotalUserCountController(() -> { throw failure; }), mapper);
            JsonNode result = compare(before, candidate(query, mapper), mapper, 500);
            assertThat(result.size()).isEqualTo(3); assertThat(result.get("code").intValue()).isEqualTo(500);
            assertThat(result.get("message").asText()).isEqualTo("서버 에러!"); assertThat(result.get("data").isNull()).isTrue();
        }
    }

    private MockMvc original(F19LegacyTotalUserCountController controller, ObjectMapper mapper) {
        return MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionRestAdvice())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
    }
    private MockMvc candidate(TotalUserCountQuery query, ObjectMapper mapper) {
        return MockMvcBuilders.standaloneSetup(new TotalUserCountController(query)).setControllerAdvice(new TotalUserCountExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
    }
    private JsonNode compare(MockMvc before, MockMvc after, ObjectMapper mapper, int status) throws Exception {
        MvcResult expected = before.perform(get("/api/v1/admin/users/stats/total-users")).andReturn();
        MvcResult actual = after.perform(get("/api/v1/admin/users/stats/total-users")).andReturn();
        assertThat(expected.getResponse().getStatus()).isEqualTo(status); assertThat(actual.getResponse().getStatus()).isEqualTo(status);
        assertThat(expected.getResponse().getHeaders("Set-Cookie")).isEmpty(); assertThat(actual.getResponse().getHeaders("Set-Cookie")).isEmpty();
        assertThat(expected.getResponse().getHeader("Cache-Control")).isNull(); assertThat(actual.getResponse().getHeader("Cache-Control")).isNull();
        JsonNode result = mapper.readTree(actual.getResponse().getContentAsByteArray());
        assertThat(result).isEqualTo(mapper.readTree(expected.getResponse().getContentAsByteArray())); return result;
    }
}
