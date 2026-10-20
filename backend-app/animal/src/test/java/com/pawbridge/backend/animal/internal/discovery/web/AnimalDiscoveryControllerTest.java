package com.pawbridge.backend.animal.internal.discovery.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery;
import com.pawbridge.backend.animal.internal.discovery.application.AnimalDiscovery.*;
import com.pawbridge.backend.animal.internal.discovery.application.AnimalNotFoundException;
import com.pawbridge.backend.animal.internal.discovery.application.SearchProjectionPendingException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.format.support.DefaultFormattingConversionService;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AnimalDiscoveryControllerTest {
    private final AnimalDiscovery animals = mock(AnimalDiscovery.class);
    private final ObjectMapper json = JsonMapper.builder().addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
    private MockMvc mvc;

    @BeforeEach void prepare() {
        mvc = MockMvcBuilders.standaloneSetup(new AnimalDiscoveryController(animals))
                .setControllerAdvice(new AnimalDiscoveryExceptionHandler())
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .setConversionService(new DefaultFormattingConversionService())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build();
        when(animals.search(any(), any())).thenReturn(new Page(List.of(summary()), 0, 20, 1, 1, true, true));
    }

    @Test void list_has_exact_seven_page_fields_default_sort_and_non_null_source_summary() throws Exception {
        JsonNode body = json.readTree(mvc.perform(get("/api/v1/animals")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(body.size()).isEqualTo(7);
        assertThat(body.has("content") && body.has("number") && body.has("size") && body.has("totalElements")
                && body.has("totalPages") && body.has("first") && body.has("last")).isTrue();
        assertThat(body.path("content").get(0).path("species").asText()).isEqualTo("DOG");
        assertThat(body.path("content").get(0).path("createdAt").asText()).isEqualTo("2026-09-01T13:14:15.123456");
        assertThat(body.path("content").get(0).has("birthYear")).isFalse();
        assertThat(body.path("content").get(0).has("age")).isFalse();
        assertThat(body.path("content").get(0).has("weight")).isFalse();
        ArgumentCaptor<PageRequest> page = ArgumentCaptor.forClass(PageRequest.class);
        verify(animals).search(eq(Search.empty()), page.capture());
        assertThat(page.getValue()).isEqualTo(new PageRequest(0, 20, List.of(new Order("createdAt", false)), true));
    }

    @Test void binds_every_filter_enum_iso_date_and_requested_page_sort() throws Exception {
        mvc.perform(get("/api/v1/animals").param("species", "CAT").param("breed", "코리안 숏헤어")
                .param("gender", "FEMALE").param("neuterStatus", "YES").param("status", "ADOPTED")
                .param("minAge", "2").param("maxAge", "8").param("region", "서울").param("city", "강남")
                .param("keyword", "귀가 접힌").param("noticeNo", "서울-1").param("shelterId", "11")
                .param("intakeFrom", "2026-09-01").param("intakeTo", "2026-09-30")
                .param("page", "2").param("size", "30").param("sort", "age,asc")).andExpect(status().isOk());
        verify(animals).search(new Search(Species.CAT, "코리안 숏헤어", Gender.FEMALE, NeuterStatus.YES,
                AnimalStatus.ADOPTED, 2, 8, "서울", "강남", "귀가 접힌", "서울-1", 11L,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)),
                new PageRequest(2, 30, List.of(new Order("age", true)), true));
    }

    @Test void malformed_enum_and_date_are_binding_400_before_query() throws Exception {
        var wrongSpecies = mvc.perform(get("/api/v1/animals").param("species", "dog"))
                .andExpect(status().isBadRequest()).andReturn().getResponse();
        var wrongDate = mvc.perform(get("/api/v1/animals").param("intakeFrom", "2026-02-30"))
                .andExpect(status().isBadRequest()).andReturn().getResponse();
        assertThat(json.readTree(wrongSpecies.getContentAsString()).path("message").asText()).startsWith("species: ");
        assertThat(json.readTree(wrongDate.getContentAsString()).path("message").asText()).startsWith("intakeFrom: ");
        verifyNoInteractions(animals);
    }

    @Test void missing_detail_condition_error_and_pending_projection_keep_source_error_shapes() throws Exception {
        when(animals.findById(72)).thenThrow(new AnimalNotFoundException());
        assertError("/api/v1/animals/72", 404, "동물을 찾을 수 없습니다");
        doThrow(new IllegalArgumentException("지원하지 않는 정렬 기준입니다: unknown")).when(animals).search(any(), any());
        assertError("/api/v1/animals", 400, "지원하지 않는 정렬 기준입니다: unknown");
        doThrow(new SearchProjectionPendingException()).when(animals).search(any(), any());
        assertError("/api/v1/animals", 503, "검색 정보를 갱신 중입니다. 잠시 후 다시 시도해주세요");
    }

    @Test void detail_exposes_all_28_source_fields_without_envelope_or_shelter_private_data() throws Exception {
        LocalDate date = LocalDate.of(2026, 9, 1);
        LocalDateTime time = date.atTime(13, 14, 15, 123456000);
        when(animals.findById(72)).thenReturn(new Details(72L, "N-72", Species.DOG, "말티즈", Gender.FEMALE,
                NeuterStatus.YES, 2022, 4, "3.5(Kg)", "흰색", "접힌 귀", AnimalStatus.ADOPTED,
                date, date.plusDays(10), "강남", date.minusDays(1), "one", "two", "설명", 0, 8L, "보호소",
                "D-72", "종료(입양)", time, ApiSource.APMS_ANIMAL, time, time));
        JsonNode body = json.readTree(mvc.perform(get("/api/v1/animals/72")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(body.size()).isEqualTo(28);
        assertThat(body.path("status").asText()).isEqualTo("ADOPTED");
        assertThat(body.path("weight").asText()).isEqualTo("3.5(Kg)");
        assertThat(body.path("happenDate").asText()).isEqualTo("2026-08-31");
        assertThat(body.path("apmsUpdatedAt").asText()).isEqualTo("2026-09-01T13:14:15.123456");
        assertThat(body.has("data") || body.has("shelterAddress") || body.has("shelterPhone")).isFalse();
    }

    @Test void nested_similarity_and_apms_endpoints_are_not_mapped_in_this_bundle() throws Exception {
        mvc.perform(get("/api/v1/animals/72/similar")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/animals/apms/D-72")).andExpect(status().isNotFound());
        verifyNoInteractions(animals);
    }

    private void assertError(String url, int code, String message) throws Exception {
        var response = mvc.perform(get(url)).andExpect(status().is(code)).andReturn().getResponse();
        JsonNode body = json.readTree(response.getContentAsString());
        assertThat(body.size()).isEqualTo(3);
        assertThat(body.path("code").asInt()).isEqualTo(code);
        assertThat(body.path("message").asText()).isEqualTo(message);
        assertThat(body.has("data") && body.path("data").isNull()).isTrue();
    }
    private static Summary summary() {
        return new Summary(72L, "N-72", Species.DOG, "말티즈", Gender.FEMALE, null, null, null,
                AnimalStatus.PROTECT, LocalDate.of(2026, 9, 11), "one", 0, 8L, "보호소",
                LocalDateTime.of(2026, 9, 1, 13, 14, 15, 123456000));
    }
}
