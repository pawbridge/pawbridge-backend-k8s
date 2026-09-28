package com.pawbridge.communityservice.service;

import com.pawbridge.communityservice.domain.entity.AnimalReport;
import com.pawbridge.communityservice.domain.entity.BoardType;
import com.pawbridge.communityservice.domain.repository.AnimalReportRepository;
import com.pawbridge.communityservice.dto.request.CreateAnimalReportRequest;
import com.pawbridge.communityservice.dto.request.CreatePostRequest;
import com.pawbridge.communityservice.dto.response.PostResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AnimalReportServiceTest {
    @Mock PostService postService;
    @Mock AnimalReportRepository reportRepository;

    AnimalReportService service;

    @BeforeEach
    void setUp() {
        service = new AnimalReportService(postService, reportRepository);
    }

    @Test
    void givenSightingWithPhoto_whenCreate_thenUseReportBoardAndPersistStructuredDetail() {
        CreateAnimalReportRequest request = request(AnimalReport.Kind.SIGHTING);
        MockMultipartFile photo = new MockMultipartFile("photos", "animal.jpg", "image/jpeg",
                new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1});
        PostResponse post = post(7L, BoardType.REPORT);
        when(postService.createPost(any(CreatePostRequest.class), any(), eq(3L))).thenReturn(post);
        when(reportRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.create(request, new MockMultipartFile[]{photo}, 3L);

        verify(postService).createPost(argThat(created -> created.boardType() == BoardType.REPORT
                && created.content().equals("공원에서 보았습니다.")
                && created.title().contains("서울 마포구")), any(), eq(3L));
        verify(reportRepository).save(argThat(detail -> detail.getPostId().equals(7L)
                && detail.getKind() == AnimalReport.Kind.SIGHTING));
        assertThat(result.legacy()).isFalse();
    }

    @Test
    void givenMoreThanFivePhotos_whenCreate_thenRejectBeforeUploadOrDatabaseWrite() {
        MockMultipartFile[] photos = new MockMultipartFile[6];
        for (int i = 0; i < photos.length; i++) {
            photos[i] = new MockMultipartFile("photos", "animal.jpg", "image/jpeg",
                    new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff});
        }

        assertThatThrownBy(() -> service.create(request(AnimalReport.Kind.MISSING), photos, 3L))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(postService, reportRepository);
    }

    @Test
    void givenUnsupportedPhotoContents_whenCreate_thenRejectBeforeUpload() {
        MockMultipartFile disguised = new MockMultipartFile("photos", "animal.jpg", "image/jpeg",
                "not-a-jpeg".getBytes());

        assertThatThrownBy(() -> service.create(request(AnimalReport.Kind.SIGHTING),
                new MockMultipartFile[]{disguised}, 3L))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(postService, reportRepository);
    }

    @Test
    void givenNewPhoto_whenUpdating_thenRejectBeforeTouchingExistingImages() {
        MockMultipartFile photo = new MockMultipartFile("photos", "animal.jpg", "image/jpeg",
                new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff});

        assertThatThrownBy(() -> service.update(7L, request(AnimalReport.Kind.MISSING),
                new MockMultipartFile[]{photo}, 3L)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(postService, reportRepository);
    }

    @Test
    void givenOldMissingPostWithoutDetail_whenRead_thenPreserveLegacyPost() {
        PostResponse post = post(7L, BoardType.MISSING);
        when(postService.getPost(7L)).thenReturn(post);
        when(reportRepository.findById(7L)).thenReturn(Optional.empty());

        var result = service.get(7L);

        assertThat(result.post()).isSameAs(post);
        assertThat(result.detail()).isNull();
        assertThat(result.legacy()).isTrue();
    }

    @Test
    void givenPagedReportPosts_whenListing_thenFetchDetailsOnlyForThatPage() {
        var pageable = PageRequest.of(1, 12);
        when(postService.getPostsByBoardTypes(List.of(BoardType.MISSING, BoardType.REPORT), pageable))
                .thenReturn(new PageImpl<>(List.of(post(7L, BoardType.MISSING)), pageable, 13));
        when(reportRepository.findAllById(List.of(7L))).thenReturn(List.of());

        var page = service.list(pageable);

        assertThat(page.getTotalElements()).isEqualTo(13);
        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).legacy()).isTrue();
        verify(reportRepository).findAllById(List.of(7L));
    }

    @Test
    void givenNonReportPost_whenRead_thenDoNotExposeItAsAnimalReport() {
        when(postService.getPost(7L)).thenReturn(post(7L, BoardType.COMMUNICATION));

        assertThatThrownBy(() -> service.get(7L)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(reportRepository);
    }

    private static CreateAnimalReportRequest request(AnimalReport.Kind kind) {
        return new CreateAnimalReportRequest(kind, LocalDate.now(), null, "서울 마포구", "공원",
                "개", null, "갈색", "소형", "귀가 접힘", "북쪽", "공원에서 보았습니다.");
    }

    private static PostResponse post(Long id, BoardType type) {
        return new PostResponse(id, 3L, "작성자", "제목", "내용", type, List.of(), null, null);
    }
}
