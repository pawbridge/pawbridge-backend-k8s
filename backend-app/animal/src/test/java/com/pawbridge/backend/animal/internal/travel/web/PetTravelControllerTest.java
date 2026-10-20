package com.pawbridge.backend.animal.internal.travel.web;
import com.pawbridge.backend.animal.internal.travel.application.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PetTravelControllerTest {
    private static final ObjectMapper JSON=new ObjectMapper().registerModule(new JavaTimeModule());
    private final PetTravel service = mock(PetTravel.class);
    private MockMvc mvc;
    @BeforeEach void setUp() { mvc = MockMvcBuilders.standaloneSetup(new PetTravelController(service)).setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper().registerModule(new JavaTimeModule()))).build(); }

    @Test
    void givenRegionList__whenGet__thenSerializePublicResponse() throws Exception {
        when(service.regions()).thenReturn(new PetTravelResponse.Regions(List.of(new PetTravelResponse.Region("1", "서울")), Instant.EPOCH));
        mvc.perform(get("/api/v1/places/regions")).andExpect(status().isOk())
                .andExpect(result -> assertThat(JSON.readTree(result.getResponse().getContentAsByteArray()).at("/items/0/code")).isEqualTo(JSON.valueToTree("1"))).andExpect(result -> assertThat(JSON.readTree(result.getResponse().getContentAsByteArray()).at("/items/0/name")).isEqualTo(JSON.valueToTree("서울")));
    }

    @Test
    void givenArea__whenList__thenBindAreaAndExposePreviewBoundary() throws Exception {
        when(service.places("1",0)).thenReturn(new PetTravelResponse.Places("1", List.of(), true, Instant.EPOCH));
        mvc.perform(get("/api/v1/places").param("areaCode", "1")).andExpect(status().isOk())
                .andExpect(result -> assertThat(JSON.readTree(result.getResponse().getContentAsByteArray()).at("/previewOnly")).isEqualTo(JSON.valueToTree(true))).andExpect(result -> assertThat(JSON.readTree(result.getResponse().getContentAsByteArray()).at("/items")).isEqualTo(JSON.createArrayNode()));
    }

    @Test
    void givenInvalidArea__whenGet__thenReturn400WithoutInputEcho() throws Exception {
        when(service.places(null,0)).thenThrow(new PetTravelException(PetTravelException.Code.INVALID_REQUEST));
        mvc.perform(get("/api/v1/places")).andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(JSON.readTree(result.getResponse().getContentAsByteArray()).at("/code")).isEqualTo(JSON.valueToTree("PET_TRAVEL_INVALID_REQUEST")));
    }

    @Test void givenPage__whenGet__thenBindAndSerializePageMetadata() throws Exception {
        when(service.places("11",2)).thenReturn(new PetTravelResponse.Places("11",List.of(),true,Instant.EPOCH,"PARTIAL",2,10,21,3));
        mvc.perform(get("/api/v1/places").param("areaCode","11").param("page","2"))
                .andExpect(status().isOk()).andExpect(result -> assertThat(JSON.readTree(result.getResponse().getContentAsByteArray()).at("/page")).isEqualTo(JSON.valueToTree(2)))
                .andExpect(result -> assertThat(JSON.readTree(result.getResponse().getContentAsByteArray()).at("/size")).isEqualTo(JSON.valueToTree(10))).andExpect(result -> assertThat(JSON.readTree(result.getResponse().getContentAsByteArray()).at("/totalElements")).isEqualTo(JSON.valueToTree(21)))
                .andExpect(result -> assertThat(JSON.readTree(result.getResponse().getContentAsByteArray()).at("/totalPages")).isEqualTo(JSON.valueToTree(3))).andExpect(result -> assertThat(JSON.readTree(result.getResponse().getContentAsByteArray()).at("/availability")).isEqualTo(JSON.valueToTree("PARTIAL")));
    }

    @ParameterizedTest @ValueSource(strings={"abc","1.5","2147483648"})
    void givenMalformedPage__whenGet__then400WithoutServiceCall(String page) throws Exception {
        mvc.perform(get("/api/v1/places").param("areaCode","11").param("page",page))
                .andExpect(status().isBadRequest()).andExpect(result -> assertThat(JSON.readTree(result.getResponse().getContentAsByteArray()).at("/code")).isEqualTo(JSON.valueToTree("PET_TRAVEL_INVALID_REQUEST")));
        verifyNoInteractions(service);
    }

    @Test
    void givenUnavailable__whenGet__then503AndNoStore() throws Exception {
        when(service.regions()).thenThrow(PetTravelException.unavailable());
        mvc.perform(get("/api/v1/places/regions")).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(result -> assertThat(JSON.readTree(result.getResponse().getContentAsByteArray()).at("/code")).isEqualTo(JSON.valueToTree("PET_TRAVEL_UNAVAILABLE")));
    }

    @Test
    void givenTransactionCannotStart__whenList__then503AndNoStore() throws Exception {
        when(service.places("11", 0)).thenThrow(new org.springframework.transaction.CannotCreateTransactionException(
                "Could not start transaction", new java.sql.SQLTransientConnectionException("Connection unavailable")));
        var mvcWithAdvice = MockMvcBuilders.standaloneSetup(new PetTravelController(service))
                .build();

        mvcWithAdvice.perform(get("/api/v1/places").param("areaCode", "11"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(result -> assertThat(JSON.readTree(result.getResponse().getContentAsByteArray()).at("/code")).isEqualTo(JSON.valueToTree("PET_TRAVEL_UNAVAILABLE")));
    }

    @Test
    void givenMissingPlace__whenGet__then404() throws Exception {
        when(service.detail("123")).thenThrow(new PetTravelException(PetTravelException.Code.NOT_FOUND));
        mvc.perform(get("/api/v1/places/123")).andExpect(status().isNotFound())
                .andExpect(result -> assertThat(JSON.readTree(result.getResponse().getContentAsByteArray()).at("/code")).isEqualTo(JSON.valueToTree("PET_TRAVEL_NOT_FOUND")));
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
                .andExpect(result -> assertThat(JSON.readTree(result.getResponse().getContentAsByteArray()).at("/visitInformation/usageHours")).isEqualTo(JSON.valueToTree("09:00~18:00")))
                .andExpect(result -> assertThat(JSON.readTree(result.getResponse().getContentAsByteArray()).at("/visitInformation/experienceGuide")).isEqualTo(JSON.valueToTree("해설 프로그램")))
                .andExpect(result -> assertThat(JSON.readTree(result.getResponse().getContentAsByteArray()).at("/visitInformation/additionalItems/0/name")).isEqualTo(JSON.valueToTree("입장료")))
                .andExpect(result -> assertThat(JSON.readTree(result.getResponse().getContentAsByteArray()).at("/images/0/name")).isEqualTo(JSON.valueToTree("전경")))
                .andExpect(result -> assertThat(JSON.readTree(result.getResponse().getContentAsByteArray()).at("/imagesStatus")).isEqualTo(JSON.valueToTree("READY")));
    }
}
