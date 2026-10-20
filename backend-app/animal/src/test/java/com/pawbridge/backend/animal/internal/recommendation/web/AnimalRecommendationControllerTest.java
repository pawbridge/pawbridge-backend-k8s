package com.pawbridge.backend.animal.internal.recommendation.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.*;
import com.pawbridge.backend.animal.internal.discovery.application.AnimalNotFoundException;
import com.pawbridge.backend.animal.internal.recommendation.application.AnimalRecommendations;
import com.pawbridge.backend.animal.internal.recommendation.application.AnimalRecommendations.Card;
import com.pawbridge.backend.animal.internal.recommendation.application.RecommendationUnavailableException;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AnimalRecommendationControllerTest {
    private final AnimalRecommendations recommendations = mock(AnimalRecommendations.class);
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AnimalRecommendationController(recommendations))
            .setControllerAdvice(new AnimalRecommendationExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();

    @Test
    void givenStoredCardFields__whenGettingSimilarAnimals__thenReturnOriginalArrayWithAll18FieldsInOrder() throws Exception {
        when(recommendations.recommend(1)).thenReturn(List.of(new Card(4L, "공고-4", Species.DOG, "믹스", Gender.FEMALE,
                2023, 3, "0.69(Kg)", "레몬색&흰색", LocalDate.of(2026, 9, 29), "귀 반점", AnimalStatus.PROTECT,
                LocalDate.of(2026, 10, 9), "https://example.test/a.jpg", 7, 8L, "보호소",
                LocalDateTime.of(2026, 9, 29, 13, 4, 5))));
        JsonNode actual = json(mvc.perform(get("/api/v1/animals/1/similar")).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(actual.isArray()).isTrue();
        assertThat(actual.size()).isOne();
        assertThat(actual.get(0)).isEqualTo(mapper.readTree("""
                {"id":4,"apmsNoticeNo":"공고-4","species":"DOG","breed":"믹스","gender":"FEMALE",
                "birthYear":2023,"age":3,"weight":"0.69(Kg)","color":"레몬색&흰색","happenDate":"2026-09-29",
                "specialMark":"귀 반점","status":"PROTECT","noticeEndDate":"2026-10-09",
                "imageUrl":"https://example.test/a.jpg","favoriteCount":7,"shelterId":8,
                "shelterName":"보호소","createdAt":"2026-09-29T13:04:05"}
                """));
    }

    @Test
    void givenMissingOptionalFieldsOrNoCandidates__whenGettingSimilarAnimals__thenOmitNullFieldsOrReturnEmptyArray() throws Exception {
        when(recommendations.recommend(1)).thenReturn(List.of(new Card(4L, null, Species.CAT, null, null, null, null,
                null, null, null, null, AnimalStatus.NOTICE, null, null, null, null, null, null)));
        var response = mvc.perform(get("/api/v1/animals/1/similar")).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(json(response.getContentAsString())).isEqualTo(mapper.readTree("[{\"id\":4,\"species\":\"CAT\",\"status\":\"NOTICE\"}]"));
        when(recommendations.recommend(1)).thenReturn(List.of());
        assertThat(mvc.perform(get("/api/v1/animals/1/similar")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString()).isEqualTo("[]");
    }

    @ParameterizedTest
    @MethodSource("failures")
    void givenSourceOrUpstreamOrDatabaseFailure__whenGettingSimilarAnimals__thenKeepOriginalStatusAndSafeEnvelope(
            RuntimeException failure, int code, String message) throws Exception {
        when(recommendations.recommend(1)).thenThrow(failure);
        var response = mvc.perform(get("/api/v1/animals/1/similar")).andExpect(status().is(code)).andReturn().getResponse();
        var actual = json(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(actual).isEqualTo(mapper.createObjectNode().put("code", code).put("message", message).putNull("data"));
    }
    static Stream<Arguments> failures() {
        return Stream.of(Arguments.of(new AnimalNotFoundException(), 404, "동물을 찾을 수 없습니다"),
                Arguments.of(new RecommendationUnavailableException(), 503, "추천 자료를 준비 중입니다. 잠시 후 다시 시도해 주세요"),
                Arguments.of(new DataAccessResourceFailureException("synthetic private database detail"), 500, "데이터베이스 오류가 발생했습니다."),
                Arguments.of(new IllegalStateException("synthetic private failure"), 500, "서버 내부 오류가 발생했습니다."));
    }

    @Test
    void givenNonNumericId__whenBindingPath__thenKeepSourceCatchAll500WithoutCallingApplication() throws Exception {
        mvc.perform(get("/api/v1/animals/invalid/similar")).andExpect(status().isInternalServerError());
        verifyNoInteractions(recommendations);
    }
    private JsonNode json(String source) throws Exception { return mapper.readTree(source); }
}
