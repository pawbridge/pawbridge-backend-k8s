package com.pawbridge.communityservice.service;

import com.pawbridge.communityservice.controller.AnimalReportController;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class AnimalReportPageBoundaryTest {
    @Test
    void givenOversizedPage_whenListing_thenRejectBeforeRepositoryCall() {
        AnimalReportService service = mock(AnimalReportService.class);

        assertThatThrownBy(() -> new AnimalReportController(service).list(0, 51, null, null))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(service);
    }
}
