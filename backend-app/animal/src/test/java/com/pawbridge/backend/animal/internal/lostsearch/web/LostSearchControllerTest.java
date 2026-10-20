package com.pawbridge.backend.animal.internal.lostsearch.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pawbridge.backend.animal.internal.lostsearch.infrastructure.*;
import com.pawbridge.backend.animal.internal.command.infrastructure.dto.response.AnimalDetailResponse;
import com.pawbridge.backend.animal.internal.command.infrastructure.enums.Species;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

class LostSearchControllerTest {
    final LostSearchService service=mock(LostSearchService.class);
    final ObjectMapper json=new ObjectMapper();
    MockMvc mvc;
    @BeforeEach void setUp() { mvc=MockMvcBuilders.standaloneSetup(new LostSearchController(service)).build(); }

    @Test void multipart_post_binds_original_request_and_returns_candidate_without_image_score() throws Exception {
        var animal=AnimalDetailResponse.builder().id(7L).species(Species.DOG).build();
        when(service.search(any())).thenAnswer(invocation->{
            LostSearchRequest request=invocation.getArgument(0);assertThat(request.getSpecies()).isEqualTo(Species.DOG);
            assertThat(request.getImage().getBytes()).isEqualTo(new byte[]{1});assertThat(request.getRegion()).isEqualTo("서울");
            return new LostSearchResponse(List.of(new LostSearchResponse.Candidate(animal,"02-0000",List.of("DISCOVERY_PLACE_TEXT_MATCH"))));
        });
        var response=mvc.perform(multipart("/api/v1/animals/lost-candidates").file(new MockMultipartFile("image",new byte[]{1}))
                .param("species","DOG").param("region","서울")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);var result=json.readTree(response.getContentAsByteArray());
        var candidate=result.get("candidates").get(0);assertThat(candidate.get("animal").get("id").asLong()).isEqualTo(7);
        assertThat(candidate.get("shelterPhone").asText()).isEqualTo("02-0000");assertThat(candidate.has("imageScore")).isFalse();
    }

    @Test void invalid_date_binding_keeps_original_400_message_before_service() throws Exception {
        var response=mvc.perform(multipart("/api/v1/animals/lost-candidates").file(new MockMultipartFile("image",new byte[]{1}))
                .param("species","DOG").param("lostDate","invalid-date")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(400);assertThat(json.readTree(response.getContentAsByteArray()).get("message").asText())
                .isEqualTo("사진·종류·날짜·지역·특징 입력을 확인해 주세요");verifyNoInteractions(service);
    }

    @ParameterizedTest @ValueSource(ints={400,413,503})
    void service_failure_keeps_status_message_and_only_503_retry_after(int status) throws Exception {
        when(service.search(any())).thenThrow(new ResponseStatusException(HttpStatus.valueOf(status),"synthetic-safe-message"));
        var response=mvc.perform(multipart("/api/v1/animals/lost-candidates").file(new MockMultipartFile("image",new byte[]{1}))
                .param("species","DOG")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(json.readTree(response.getContentAsByteArray()).get("message").asText()).isEqualTo("synthetic-safe-message");
        assertThat(response.getHeader("Retry-After")).isEqualTo(status==503?"3":null);
    }
}
