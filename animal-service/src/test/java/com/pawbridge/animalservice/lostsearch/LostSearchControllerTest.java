package com.pawbridge.animalservice.lostsearch;

import com.pawbridge.animalservice.exception.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.mockito.ArgumentCaptor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LostSearchControllerTest {
    private final LostSearchService service = mock(LostSearchService.class);
    private MockMvc mvc;
    private static final String PATH = "/api/v1/animals/lost-candidates";

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new LostSearchController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void givenPhotoAndSpecies__whenSearch__thenReturnNormalEmptyResult() throws Exception {
        when(service.search(any())).thenReturn(new LostSearchResponse(List.of()));
        mvc.perform(multipart(PATH).file(photo()).param("species", "DOG").param("lostDate", ""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.candidates").isEmpty());
        var request = ArgumentCaptor.forClass(LostSearchRequest.class);
        verify(service).search(request.capture());
        assertThat(request.getValue().isIncludeAdoptedOrReturned()).isFalse();
    }

    @Test
    void givenResolvedOption__whenSearch__thenBindExplicitSelection() throws Exception {
        when(service.search(any())).thenReturn(new LostSearchResponse(List.of()));
        mvc.perform(multipart(PATH).file(photo()).param("species", "DOG")
                        .param("includeAdoptedOrReturned", "true"))
                .andExpect(status().isOk());
        var request = ArgumentCaptor.forClass(LostSearchRequest.class);
        verify(service).search(request.capture());
        assertThat(request.getValue().isIncludeAdoptedOrReturned()).isTrue();
    }

    @Test
    void givenInvalidInput__whenSearch__thenRejectBeforeService() throws Exception {
        mvc.perform(multipart(PATH).file(photo())).andExpect(status().isBadRequest());
        mvc.perform(multipart(PATH).param("species", "DOG")).andExpect(status().isBadRequest());
        mvc.perform(multipart(PATH).file(photo()).param("species", "DOG").param("lostDate", "not-date"))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart(PATH).file(photo()).param("species", "DOG").param("description", "x".repeat(501)))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart(PATH).file(photo()).param("species", "unknown"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void givenSearchFailure__whenSearch__thenReturn503InsteadOfEmptySuccess() throws Exception {
        when(service.search(any())).thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "검색 실패"));
        mvc.perform(multipart(PATH).file(photo()).param("species", "DOG"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.message").value("검색 실패"));
    }

    @Test
    void givenMultipartLimitFailure__whenHandled__thenReturn413() throws Exception {
        when(service.search(any())).thenThrow(new MaxUploadSizeExceededException(5 * 1024 * 1024));
        mvc.perform(multipart(PATH).file(photo()).param("species", "CAT"))
                .andExpect(status().isPayloadTooLarge());
    }

    private MockMultipartFile photo() { return new MockMultipartFile("image", "photo.png", "image/png", new byte[]{1}); }
}
