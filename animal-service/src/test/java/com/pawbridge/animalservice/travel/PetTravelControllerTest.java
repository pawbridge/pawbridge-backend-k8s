package com.pawbridge.animalservice.travel;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
        when(service.places("1",0)).thenReturn(new PetTravelResponse.Places("1", List.of(), true, Instant.EPOCH));
        mvc.perform(get("/api/v1/places").param("areaCode", "1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.previewOnly").value(true)).andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void givenInvalidArea__whenGet__thenReturn400WithoutInputEcho() throws Exception {
        when(service.places(null,0)).thenThrow(new PetTravelException(PetTravelException.Code.INVALID_REQUEST));
        mvc.perform(get("/api/v1/places")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PET_TRAVEL_INVALID_REQUEST"));
    }

    @Test void givenPage__whenGet__thenBindAndSerializePageMetadata() throws Exception {
        when(service.places("11",2)).thenReturn(new PetTravelResponse.Places("11",List.of(),true,Instant.EPOCH,"PARTIAL",2,10,21,3));
        mvc.perform(get("/api/v1/places").param("areaCode","11").param("page","2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(10)).andExpect(jsonPath("$.totalElements").value(21))
                .andExpect(jsonPath("$.totalPages").value(3)).andExpect(jsonPath("$.availability").value("PARTIAL"));
    }

    @ParameterizedTest @ValueSource(strings={"abc","1.5","2147483648"})
    void givenMalformedPage__whenGet__then400WithoutServiceCall(String page) throws Exception {
        mvc.perform(get("/api/v1/places").param("areaCode","11").param("page",page))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PET_TRAVEL_INVALID_REQUEST"));
        verifyNoInteractions(service);
    }

    @Test
    void givenUnavailable__whenGet__then503AndNoStore() throws Exception {
        when(service.regions()).thenThrow(PetTravelException.unavailable());
        mvc.perform(get("/api/v1/places/regions")).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("PET_TRAVEL_UNAVAILABLE"));
    }

    @Test
    void givenTransactionCannotStart__whenList__then503AndNoStore() throws Exception {
        when(service.places("11", 0)).thenThrow(new org.springframework.transaction.CannotCreateTransactionException(
                "Could not start transaction", new java.sql.SQLTransientConnectionException("Connection unavailable")));
        var mvcWithAdvice = MockMvcBuilders.standaloneSetup(new PetTravelController(service))
                .setControllerAdvice(new com.pawbridge.animalservice.exception.GlobalExceptionHandler()).build();

        mvcWithAdvice.perform(get("/api/v1/places").param("areaCode", "11"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("PET_TRAVEL_UNAVAILABLE"));
    }

    @Test
    void givenMissingPlace__whenGet__then404() throws Exception {
        when(service.detail("123")).thenThrow(new PetTravelException(PetTravelException.Code.NOT_FOUND));
        mvc.perform(get("/api/v1/places/123")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PET_TRAVEL_NOT_FOUND"));
    }

    @Test
    void givenStoredVisitDetails__whenGetDetail__thenSerializeInformationAndGalleryContract() throws Exception {
        var place = new PetTravelResponse.Place("123", "서울 공원", "서울", null);
        var conditions = new PetTravelResponse.Conditions(null, "소형견", "목줄", null, null, null, null);
        var visit = new PetTravelResponse.VisitInformation("12", "02-123-4567", "연중", "09:00~18:00",
                "월요일", "가능", null, "무료", null, null, "해설 프로그램", "연중", null, null,
                null, List.of(new PetTravelResponse.InformationItem("입장료", "무료")), "READY", Instant.EPOCH);
        when(service.detail("123")).thenReturn(new PetTravelResponse.Detail(place, "소개", conditions, true,
                "KOREA_TOURISM_ORGANIZATION", Instant.EPOCH, Instant.EPOCH, "READY", visit,
                List.of(new PetTravelResponse.Image("https://tong.visitkorea.or.kr/cms/resource/1/a.jpg",
                        null, "전경", "Type1")), "READY", Instant.EPOCH));

        mvc.perform(get("/api/v1/places/123")).andExpect(status().isOk())
                .andExpect(jsonPath("$.visitInformation.usageHours").value("09:00~18:00"))
                .andExpect(jsonPath("$.visitInformation.experienceGuide").value("해설 프로그램"))
                .andExpect(jsonPath("$.visitInformation.additionalItems[0].name").value("입장료"))
                .andExpect(jsonPath("$.images[0].name").value("전경"))
                .andExpect(jsonPath("$.imagesStatus").value("READY"));
    }
}
