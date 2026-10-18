package com.pawbridge.backend.community.internal.post.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pawbridge.backend.community.internal.post.application.CreatePost;
import com.pawbridge.backend.community.internal.post.application.PostSnapshot;
import com.pawbridge.backend.community.internal.post.domain.PostBoardNotAllowedException;
import com.pawbridge.backend.community.internal.post.domain.PostBoardType;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

class PostCreateControllerTest {
    @Test
    void given__multipart_media__when__controller_maps_uploads__then__stream_is_opened_lazily_by_uploader() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        InputStream bytes = new ByteArrayInputStream(new byte[] {1, 2, 3});
        when(file.getOriginalFilename()).thenReturn("photo.png");
        when(file.getContentType()).thenReturn("image/png");
        when(file.getSize()).thenReturn(3L);
        when(file.getInputStream()).thenReturn(bytes);
        CreatePost useCase = new CreatePost(files -> {
            assertEquals(1, files.size());
            assertEquals("photo.png", files.getFirst().originalFilename());
            assertEquals("image/png", files.getFirst().contentType());
            assertEquals(3L, files.getFirst().size());
            try {
                verify(file, never()).getInputStream();
                assertSame(bytes, files.getFirst().content().open());
            } catch (java.io.IOException failure) {
                throw new AssertionError(failure);
            }
            return List.of("https://example.test/photo.png");
        }, author -> Optional.of("합성 회원"), post -> {
            assertEquals(7L, post.authorId());
            return new PostSnapshot(42L, post.authorId(), post.title(), post.content(), post.boardType().name(),
                    post.imageUrls(), LocalDateTime.of(2026, 10, 18, 12, 0), LocalDateTime.of(2026, 10, 18, 12, 0));
        });

        ApiResponse<?> response = new PostCreateController(useCase).create("제목", "본문",
                PostBoardType.PROTECTION, new MultipartFile[] {file}, 7L);

        assertEquals(200, response.code());
        assertEquals("게시글이 생성되었습니다.", response.message());
        verify(file).getInputStream();
    }

    @Test
    void given__forbidden_board_with_file__when__create__then__file_stream_is_never_opened() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        CreatePost useCase = new CreatePost(files -> { throw new AssertionError("No upload"); },
                author -> { throw new AssertionError("No nickname read"); },
                post -> { throw new AssertionError("No write"); });

        assertThrows(PostBoardNotAllowedException.class, () -> new PostCreateController(useCase)
                .create("제목", "본문", PostBoardType.MISSING, new MultipartFile[] {file}, 7L));

        verify(file, never()).getInputStream();
    }
}
