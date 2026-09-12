package com.pawbridge.animalservice.travel;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PetTravelControllerTest {
    private final PetTravelService service = mock(PetTravelService.class);
    private MockMvc mvc;
    @BeforeEach void setUp() { mvc = MockMvcBuilders.standaloneSetup(new PetTravelController(service)).build(); }

    @Test
    void givenRegionList__whenGet__thenSerializePublicResponse() throws Exception {
        when(service.regions()).thenReturn(new PetTravelResponse.Regions(List.of(new PetTravelResponse.Region("1", "서울")), Instant.EPOCH));
        mvc.perform(get("/api/v1/places/regions")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].code").value("1")).andExpect(jsonPath("$.items[0].name").value("서울"));
    }

    @Test
    void givenArea__whenList__thenBindAreaAndExposePreviewBoundary() throws Exception {
        when(service.places("1")).thenReturn(new PetTravelResponse.Places("1", List.of(), true, Instant.EPOCH));
        mvc.perform(get("/api/v1/places").param("areaCode", "1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.previewOnly").value(true)).andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void givenInvalidArea__whenGet__thenReturn400WithoutInputEcho() throws Exception {
        when(service.places(null)).thenThrow(new PetTravelException(PetTravelException.Code.INVALID_REQUEST));
        mvc.perform(get("/api/v1/places")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PET_TRAVEL_INVALID_REQUEST"));
    }

    @Test
    void givenUnavailable__whenGet__then503AndNoStore() throws Exception {
        when(service.regions()).thenThrow(PetTravelException.unavailable());
        mvc.perform(get("/api/v1/places/regions")).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("PET_TRAVEL_UNAVAILABLE"));
    }

    @Test
    void givenMissingPlace__whenGet__then404() throws Exception {
        when(service.detail("123")).thenThrow(new PetTravelException(PetTravelException.Code.NOT_FOUND));
        mvc.perform(get("/api/v1/places/123")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PET_TRAVEL_NOT_FOUND"));
    }
}
