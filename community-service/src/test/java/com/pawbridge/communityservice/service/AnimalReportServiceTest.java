package com.pawbridge.communityservice.service;

import com.pawbridge.communityservice.client.UserServiceClient;
import com.pawbridge.communityservice.domain.entity.AnimalReport;
import com.pawbridge.communityservice.domain.repository.AnimalReportRepository;
import com.pawbridge.communityservice.dto.request.CreateAnimalReportRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AnimalReportServiceTest {
    @Mock AnimalReportRepository reportRepository;
    @Mock S3Service s3Service;
    @Mock UserServiceClient userServiceClient;

    AnimalReportService service;

    @BeforeEach
    void setUp() {
        service = new AnimalReportService(reportRepository, s3Service, userServiceClient);
    }

    @Test
    void givenSightingWithPhoto_whenCreate_thenPersistIndependentReport() {
        MockMultipartFile photo = photo();
        when(s3Service.uploadReportImages(any())).thenReturn(List.of("https://images.example/reports/images/a.jpg"));
        when(reportRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            AnimalReport report = invocation.getArgument(0);
            ReflectionTestUtils.setField(report, "reportId", 7L);
            return report;
        });

        var result = service.create(request(AnimalReport.Kind.SIGHTING), new MockMultipartFile[]{photo}, 3L);

        assertThat(result.reportId()).isEqualTo(7L);
        assertThat(result.kind()).isEqualTo(AnimalReport.Kind.SIGHTING);
        assertThat(result.authorId()).isEqualTo(3L);
        assertThat(result.description()).isEqualTo("공원에서 보았습니다.");
        assertThat(result.imageUrls()).hasSize(1);
        verify(reportRepository).saveAndFlush(any(AnimalReport.class));
        verify(s3Service).uploadReportImages(any());
    }

    @Test
    void givenDatabaseFailureAfterUpload_whenCreate_thenRemoveUploadedPhoto() {
        when(s3Service.uploadReportImages(any())).thenReturn(List.of("https://images.example/reports/images/a.jpg"));
        when(reportRepository.saveAndFlush(any())).thenThrow(new IllegalStateException("db unavailable"));

        assertThatThrownBy(() -> service.create(request(AnimalReport.Kind.MISSING),
                new MockMultipartFile[]{photo()}, 3L)).isInstanceOf(IllegalStateException.class);
        verify(s3Service).deleteFile("https://images.example/reports/images/a.jpg");
    }

    @Test
    void givenMoreThanFivePhotos_whenCreate_thenRejectBeforeUploadOrDatabaseWrite() {
        MockMultipartFile[] photos = new MockMultipartFile[6];
        for (int i = 0; i < photos.length; i++) photos[i] = photo();

        assertThatThrownBy(() -> service.create(request(AnimalReport.Kind.MISSING), photos, 3L))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(s3Service, reportRepository);
    }

    @Test
    void givenUnsupportedPhotoContents_whenCreate_thenRejectBeforeUpload() {
        MockMultipartFile disguised = new MockMultipartFile("photos", "animal.jpg", "image/jpeg",
                "not-a-jpeg".getBytes());

        assertThatThrownBy(() -> service.create(request(AnimalReport.Kind.SIGHTING),
                new MockMultipartFile[]{disguised}, 3L)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(s3Service, reportRepository);
    }

    @Test
    void givenNewPhoto_whenUpdating_thenRejectBeforeTouchingExistingImages() {
        assertThatThrownBy(() -> service.update(7L, request(AnimalReport.Kind.MISSING),
                new MockMultipartFile[]{photo()}, 3L)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(s3Service, reportRepository);
    }

    @Test
    void givenOtherAuthor_whenUpdating_thenForbidMutation() {
        AnimalReport report = report(AnimalReport.Kind.MISSING);
        when(reportRepository.findByReportIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> service.update(7L, request(AnimalReport.Kind.MISSING), null, 4L))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value()).isEqualTo(403));
        assertThat(report.getDescription()).isEqualTo("공원에서 보았습니다.");
    }

    @Test
    void givenAuthor_whenDeleting_thenHideReportWithoutDeletingPhotos() {
        AnimalReport report = report(AnimalReport.Kind.MISSING);
        when(reportRepository.findByReportIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(report));

        service.delete(7L, 3L);

        assertThat(report.getDeletedAt()).isNotNull();
        verifyNoInteractions(s3Service);
    }

    @Test
    void givenKindAndKeyword_whenListing_thenUseReportRepositoryNotPosts() {
        var pageable = PageRequest.of(1, 12);
        when(reportRepository.searchVisible(eq(AnimalReport.Kind.MISSING), eq("마포"), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(report(AnimalReport.Kind.MISSING)), pageable, 13));

        var page = service.list(AnimalReport.Kind.MISSING, " 마포 ", pageable);

        assertThat(page.getTotalElements()).isEqualTo(13);
        assertThat(page.getContent()).hasSize(1);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "   "})
    void givenMissingOrBlankKeyword_whenListing_thenUseEmptyTextParameter(String keyword) {
        var pageable = PageRequest.of(0, 12);
        when(reportRepository.searchVisible(AnimalReport.Kind.SIGHTING, "", pageable))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        var page = service.list(AnimalReport.Kind.SIGHTING, keyword, pageable);

        assertThat(page.getContent()).isEmpty();
        verify(reportRepository).searchVisible(AnimalReport.Kind.SIGHTING, "", pageable);
        verifyNoInteractions(s3Service, userServiceClient);
    }

    @Test
    void givenDeletedOrAbsentReport_whenRead_thenReturnNotFound() {
        when(reportRepository.findByReportIdAndDeletedAtIsNull(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(7L)).isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value()).isEqualTo(404));
    }

    private static MockMultipartFile photo() {
        return new MockMultipartFile("photos", "animal.jpg", "image/jpeg",
                new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1});
    }

    private static CreateAnimalReportRequest request(AnimalReport.Kind kind) {
        return new CreateAnimalReportRequest(kind, LocalDate.now(), null, "서울 마포구", "공원",
                "개", null, "갈색", "소형", "귀가 접힘", "북쪽", "공원에서 보았습니다.");
    }

    private static AnimalReport report(AnimalReport.Kind kind) {
        AnimalReport report = new AnimalReport(3L, kind, "공원에서 보았습니다.", List.of(),
                LocalDate.now(), null, "서울 마포구", "공원", "개", null,
                "갈색", "소형", "귀가 접힘", "북쪽");
        ReflectionTestUtils.setField(report, "reportId", 7L);
        return report;
    }
}
