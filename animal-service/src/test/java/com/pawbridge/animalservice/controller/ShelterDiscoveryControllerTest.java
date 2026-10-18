package com.pawbridge.animalservice.controller;

import com.pawbridge.animalservice.exception.GlobalExceptionHandler;
import com.pawbridge.animalservice.service.ShelterDiscoveryService;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ShelterDiscoveryControllerTest {
    private final ShelterDiscoveryService service = mock(ShelterDiscoveryService.class);
    private MockMvc mvc;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new ShelterDiscoveryController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test void binds_discovery_dates_filters_and_page() throws Exception {
        when(service.discover(anyString(), anyString(), any(), any(), anyInt(), anyInt())).thenReturn(Page.empty(org.springframework.data.domain.PageRequest.of(0, 12)));
        mvc.perform(get("/api/v1/shelters/discovery").param("intakeFrom", "2026-08-29")
                .param("intakeTo", "2026-09-27").param("keyword", "보호소").param("address", "서울")
                .param("page", "2")).andExpect(status().isOk()).andExpect(jsonPath("$.content").isArray());
        verify(service).discover("보호소", "서울", LocalDate.of(2026,8,29), LocalDate.of(2026,9,27), 2, 12);
    }

    @Test void default_period_is_inclusive_thirty_days() throws Exception {
        when(service.discover(anyString(), anyString(), any(), any(), anyInt(), anyInt())).thenReturn(Page.empty(org.springframework.data.domain.PageRequest.of(0, 12)));
        mvc.perform(get("/api/v1/shelters/discovery")).andExpect(status().isOk());
        var from = org.mockito.ArgumentCaptor.forClass(LocalDate.class);
        var to = org.mockito.ArgumentCaptor.forClass(LocalDate.class);
        verify(service).discover(eq(""), eq(""), from.capture(), to.capture(), eq(0), eq(12));
        org.assertj.core.api.Assertions.assertThat(from.getValue()).isEqualTo(to.getValue().minusDays(29));
    }

    @Test void malformed_date_is_bad_request_not_server_error() throws Exception {
        mvc.perform(get("/api/v1/shelters/discovery").param("intakeFrom", "not-a-date"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void missing_observations_are_an_empty_series_not_zeroes() throws Exception {
        when(service.observations(eq(1L), any(), any())).thenReturn(List.of());
        mvc.perform(get("/api/v1/shelters/1/observations").param("from", "2026-09-01")
                .param("to", "2026-09-27")).andExpect(status().isOk()).andExpect(content().json("[]"));
    }
}
