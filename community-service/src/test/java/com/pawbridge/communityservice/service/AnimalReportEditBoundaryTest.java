package com.pawbridge.communityservice.service;

import com.pawbridge.communityservice.controller.PostController;
import com.pawbridge.communityservice.domain.repository.AnimalReportRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AnimalReportEditBoundaryTest {
    @Test
    void givenStructuredReport_whenGenericPostEditIsRequested_thenRejectBeforePostMutation() {
        PostService postService = mock(PostService.class);
        AnimalReportRepository reports = mock(AnimalReportRepository.class);
        when(reports.existsById(7L)).thenReturn(true);
        PostController controller = new PostController(postService, reports);

        assertThatThrownBy(() -> controller.updatePost(7L, "변경", "내용", null, 3L))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(postService);
    }
}
