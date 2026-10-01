package com.pawbridge.animalservice.admin.controller;

import com.pawbridge.animalservice.admin.dto.IntakeTrendResponse;
import com.pawbridge.animalservice.admin.service.AdminStatsService;
import com.pawbridge.animalservice.exception.GlobalExceptionHandler;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import com.fasterxml.jackson.databind.SerializationFeature;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminIntakeStatsControllerTest {
    private final AdminStatsService service = mock(AdminStatsService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AdminStatsController(service))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(Jackson2ObjectMapperBuilder.json()
                    .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build()))
            .setControllerAdvice(new GlobalExceptionHandler()).build();
    private final LocalDate day = LocalDate.of(2026, 10, 2);

    @Test void givenValidRange__whenRequestingTrend__thenExposeDailyAndPreviousDayContract() throws Exception {
        when(service.getIntakeTrend(day, day)).thenReturn(new IntakeTrendResponse(day, day, List.of(), 3));
        mvc.perform(get("/api/v1/admin/stats/intake-trend").param("startDate", day.toString()).param("endDate", day.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.daily").isArray())
                .andExpect(jsonPath("$.previousDayCount").value(3)).andExpect(jsonPath("$.startDate").value(day.toString()));
    }

    @Test void givenMalformedDate__whenRequestingTrend__thenReturn400WithoutQuery() throws Exception {
        mvc.perform(get("/api/v1/admin/stats/intake-trend").param("startDate", "2026-02-30").param("endDate", day.toString()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void givenInvalidRange__whenRequestingTrend__thenReturn400() throws Exception {
        when(service.getIntakeTrend(day, day)).thenThrow(new IllegalArgumentException("invalid range"));
        mvc.perform(get("/api/v1/admin/stats/intake-trend").param("startDate", day.toString()).param("endDate", day.toString()))
                .andExpect(status().isBadRequest());
    }
}
