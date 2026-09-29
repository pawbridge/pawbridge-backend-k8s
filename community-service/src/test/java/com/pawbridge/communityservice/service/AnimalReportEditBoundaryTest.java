package com.pawbridge.communityservice.service;

import com.pawbridge.communityservice.controller.PostController;
import com.pawbridge.communityservice.domain.entity.BoardType;
import com.pawbridge.communityservice.dto.request.CreatePostRequest;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AnimalReportEditBoundaryTest {
    @Test
    void givenReportBoard_whenGenericPostCreateIsRequested_thenRejectBeforePostMutation() {
        PostService postService = mock(PostService.class);
        PostController controller = new PostController(postService);

        assertThatThrownBy(() -> controller.createPost("제목", "내용", BoardType.MISSING, null, 3L))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(postService);
    }

    @Test
    void givenDirectPostServiceCall_whenReportBoardIsRequested_thenRejectBeforeUpload() {
        var postRepository = mock(com.pawbridge.communityservice.domain.repository.PostRepository.class);
        var outbox = mock(OutboxService.class);
        var media = mock(S3Service.class);
        var users = mock(com.pawbridge.communityservice.client.UserServiceClient.class);
        PostServiceImpl service = new PostServiceImpl(postRepository, outbox, media, users);

        assertThatThrownBy(() -> service.createPost(
                new CreatePostRequest("제목", "내용", BoardType.REPORT), null, 3L))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(postRepository, outbox, media);
    }
}
