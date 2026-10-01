package com.pawbridge.communityservice.controller;

import com.pawbridge.communityservice.service.AdminPostStatsService;
import com.pawbridge.communityservice.dto.response.PostPeriodStatsResponse;
import com.pawbridge.communityservice.exception.common.GlobalExceptionRestAdvice;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import com.fasterxml.jackson.databind.SerializationFeature;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminPostStatsControllerTest {
    private final AdminPostStatsService service = mock(AdminPostStatsService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AdminPostStatsController(service))
            .setControllerAdvice(new GlobalExceptionRestAdvice())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(Jackson2ObjectMapperBuilder.json()
                    .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build())).build();
    private final LocalDate day = LocalDate.of(2026, 10, 2);

    @Test void givenValidRange__whenRequestingPeriod__thenPreserveWrapperAndPreviousDayCount() throws Exception {
        when(service.period(day, day)).thenReturn(new PostPeriodStatsResponse(day, day, List.of(), 3, List.of()));
        mvc.perform(get("/api/v1/admin/posts/stats/period").param("startDate", day.toString()).param("endDate", day.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.startDate").value(day.toString()))
                .andExpect(jsonPath("$.data.daily").isArray()).andExpect(jsonPath("$.data.previousDayCount").value(3))
                .andExpect(jsonPath("$.data.byBoardType").isArray());
    }

    @Test void givenMalformedOrMissingDate__whenRequestingPeriod__thenReturn400WithoutService() throws Exception {
        mvc.perform(get("/api/v1/admin/posts/stats/period").param("startDate", "2026-02-30").param("endDate", day.toString()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/posts/stats/period").param("startDate", day.toString())).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void givenInvalidRange__whenRequestingPeriod__thenReturn400() throws Exception {
        when(service.period(day, day)).thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid range"));
        mvc.perform(get("/api/v1/admin/posts/stats/period").param("startDate", day.toString()).param("endDate", day.toString()))
                .andExpect(status().isBadRequest());
    }
}
