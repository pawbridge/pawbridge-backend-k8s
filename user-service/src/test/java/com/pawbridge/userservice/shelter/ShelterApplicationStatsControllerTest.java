package com.pawbridge.userservice.shelter;

import com.pawbridge.userservice.exception.common.GlobalExceptionRestAdvice;
import com.pawbridge.userservice.exception.common.ErrorCode;
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

class ShelterApplicationStatsControllerTest {
    private final ShelterApplicationService service = mock(ShelterApplicationService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AdminShelterApplicationController(service))
            .setControllerAdvice(new GlobalExceptionRestAdvice())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(Jackson2ObjectMapperBuilder.json()
                    .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build())).build();
    private final LocalDate day = LocalDate.of(2026, 10, 2);

    @Test void givenValidRange__whenRequestingStats__thenPreserveWrapperAndExplicitMetrics() throws Exception {
        when(service.statistics("Bearer admin", day, day))
                .thenReturn(new ShelterApplicationStatsResponse(day, day, List.of(), 3, 11, 7, 2));
        mvc.perform(get("/api/v1/admin/users/shelter-applications/stats").header("Authorization", "Bearer admin")
                .param("startDate", day.toString()).param("endDate", day.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.startDate").value(day.toString()))
                .andExpect(jsonPath("$.data.previousDayCount").value(3)).andExpect(jsonPath("$.data.currentPending").value(11))
                .andExpect(jsonPath("$.data.approvedCount").value(7)).andExpect(jsonPath("$.data.rejectedCount").value(2));
        verify(service).statistics("Bearer admin", day, day);
        verify(service, never()).detail(any(), any());
    }

    @Test void givenMalformedOrMissingDate__whenRequestingStats__thenReturn400WithoutService() throws Exception {
        mvc.perform(get("/api/v1/admin/users/shelter-applications/stats").param("startDate", "2026-02-30").param("endDate", day.toString()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/users/shelter-applications/stats").param("startDate", day.toString()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void givenUnauthorizedOrInvalidRange__whenRequestingStats__thenPreserveErrorStatus() throws Exception {
        for (var code : List.of(ErrorCode.TOKEN_INVALID, ErrorCode.SHELTER_APPLICATION_FORBIDDEN, ErrorCode.INVALID_INPUT)) {
            doThrow(new ShelterApplicationException(code)).when(service).statistics(null, day, day);
            mvc.perform(get("/api/v1/admin/users/shelter-applications/stats").param("startDate", day.toString()).param("endDate", day.toString()))
                    .andExpect(status().is(code.getHttpStatus().value()));
        }
    }
}
