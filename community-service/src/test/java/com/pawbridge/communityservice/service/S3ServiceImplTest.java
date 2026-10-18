package com.pawbridge.communityservice.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class S3ServiceImplTest {

    private static final String BUCKET_NAME = "pawbridge-public-images";
    private static final String PUBLIC_BASE_URL = "https://images.pawbridge.kr";

    @Mock
    private S3Client s3Client;

    private S3ServiceImpl s3Service;

    @BeforeEach
    void setUp() {
        s3Service = new S3ServiceImpl(s3Client, BUCKET_NAME, PUBLIC_BASE_URL + "/", "");
    }

    @Test
    void givenImageFile__whenUpload__thenReturnPublicR2Url() {
        MockMultipartFile image = new MockMultipartFile(
                "files",
                "puppy.png",
                "image/png",
                new byte[]{1, 2, 3}
        );

        List<String> uploadedUrls = s3Service.uploadImages(new MockMultipartFile[]{image});

        ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(requestCaptor.capture(), any(RequestBody.class));

        PutObjectRequest request = requestCaptor.getValue();
        assertThat(request.bucket()).isEqualTo(BUCKET_NAME);
        assertThat(request.key()).startsWith("posts/images/").endsWith(".png");
        assertThat(uploadedUrls).containsExactly(PUBLIC_BASE_URL + "/" + request.key());
    }

    @Test
    void givenReportPhoto_whenUpload__thenUseReportObjectPrefix() {
        MockMultipartFile image = new MockMultipartFile("photos", "puppy.png", "image/png", new byte[]{1, 2, 3});

        s3Service.uploadReportImages(new MockMultipartFile[]{image});

        ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(requestCaptor.capture(), any(RequestBody.class));
        assertThat(requestCaptor.getValue().key()).startsWith("reports/images/").endsWith(".png");
    }

    @Test
    void givenSecondReportPhotoFails_whenUpload_thenRemoveFirstUploadedObject() {
        MockMultipartFile first = new MockMultipartFile("photos", "first.png", "image/png", new byte[]{1});
        MockMultipartFile second = new MockMultipartFile("photos", "second.png", "image/png", new byte[]{2});
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(null)
                .thenThrow(new IllegalStateException("storage unavailable"));

        assertThatThrownBy(() -> s3Service.uploadReportImages(new MockMultipartFile[]{first, second}))
                .isInstanceOf(RuntimeException.class);

        ArgumentCaptor<DeleteObjectRequest> deleted = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(deleted.capture());
        assertThat(deleted.getValue().key()).startsWith("reports/images/").endsWith(".png");
    }

    @Test
    void givenPublicR2Url__whenDelete__thenDeleteMatchingObjectKey() {
        String objectKey = "posts/images/puppy.png";

        s3Service.deleteFile(PUBLIC_BASE_URL + "/" + objectKey);

        ArgumentCaptor<DeleteObjectRequest> requestCaptor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(requestCaptor.capture());
        assertThat(requestCaptor.getValue().bucket()).isEqualTo(BUCKET_NAME);
        assertThat(requestCaptor.getValue().key()).isEqualTo(objectKey);
    }

    @Test
    void givenOtherStorageUrl__whenDelete__thenDoNotDeleteObject() {
        s3Service.deleteFile("https://other.example.com/posts/images/puppy.png");

        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "dev/"})
    void givenDevPrefix__whenUploadReportPhoto__thenUseDevKeyAndPublicUrl(String prefix) {
        s3Service = new S3ServiceImpl(s3Client, BUCKET_NAME, PUBLIC_BASE_URL, prefix);
        MockMultipartFile image = new MockMultipartFile("photos", "puppy.png", "image/png", new byte[]{1});

        List<String> uploadedUrls = s3Service.uploadReportImages(new MockMultipartFile[]{image});

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(request.capture(), any(RequestBody.class));
        assertThat(request.getValue().bucket()).isEqualTo(BUCKET_NAME);
        assertThat(request.getValue().key()).startsWith("dev/reports/images/").endsWith(".png");
        assertThat(uploadedUrls).containsExactly(PUBLIC_BASE_URL + "/" + request.getValue().key());
    }

    @ParameterizedTest
    @ValueSource(strings = {"image/png", "video/mp4"})
    void givenDevPrefix__whenUploadPostFile__thenUseDevImageOrVideoKey(String contentType) {
        s3Service = new S3ServiceImpl(s3Client, BUCKET_NAME, PUBLIC_BASE_URL, "dev/");
        String filename = contentType.equals("image/png") ? "puppy.png" : "puppy.mp4";
        MockMultipartFile file = new MockMultipartFile("files", filename, contentType, new byte[]{1});

        List<String> uploadedUrls = s3Service.uploadImages(new MockMultipartFile[]{file});

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(request.capture(), any(RequestBody.class));
        assertThat(request.getValue().key()).startsWith(contentType.equals("image/png")
                ? "dev/posts/images/" : "dev/posts/videos/");
        assertThat(uploadedUrls).containsExactly(PUBLIC_BASE_URL + "/" + request.getValue().key());
    }

    @Test
    void givenDevPrefixAndSecondReportPhotoFails__whenUpload__thenOnlyCleanUpDevObject() {
        s3Service = new S3ServiceImpl(s3Client, BUCKET_NAME, PUBLIC_BASE_URL, "dev/");
        MockMultipartFile first = new MockMultipartFile("photos", "first.png", "image/png", new byte[]{1});
        MockMultipartFile second = new MockMultipartFile("photos", "second.png", "image/png", new byte[]{2});
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(null).thenThrow(new IllegalStateException("storage unavailable"));

        assertThatThrownBy(() -> s3Service.uploadReportImages(new MockMultipartFile[]{first, second}))
                .isInstanceOf(RuntimeException.class);

        ArgumentCaptor<PutObjectRequest> uploaded = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client, org.mockito.Mockito.times(2)).putObject(uploaded.capture(), any(RequestBody.class));
        ArgumentCaptor<DeleteObjectRequest> deleted = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(deleted.capture());
        assertThat(deleted.getValue().key()).isEqualTo(uploaded.getAllValues().get(0).key())
                .startsWith("dev/reports/images/");
    }

    @Test
    void givenDevObjectUrl__whenDelete__thenDeleteExactDevKey() {
        s3Service = new S3ServiceImpl(s3Client, BUCKET_NAME, PUBLIC_BASE_URL, "dev/");
        String key = "dev/reports/images/puppy.png";

        s3Service.deleteFile(PUBLIC_BASE_URL + "/" + key);

        ArgumentCaptor<DeleteObjectRequest> request = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(request.capture());
        assertThat(request.getValue().bucket()).isEqualTo(BUCKET_NAME);
        assertThat(request.getValue().key()).isEqualTo(key);
    }

    @ParameterizedTest
    @ValueSource(strings = {"reports/images/production.png", "posts/images/production.png",
            "dev-other/reports/images/puppy.png", "dev", "dev/", "dev/../reports/images/puppy.png",
            "dev/reports/./puppy.png", "dev//puppy.png", "dev/%2e%2e/puppy.png",
            "dev/reports/puppy.png?other=1", "dev/reports/puppy.png#other", "dev\\reports\\puppy.png"})
    void givenDevPrefixAndOutOfScopeOrAmbiguousUrl__whenDelete__thenDoNotCallStorage(String key) {
        s3Service = new S3ServiceImpl(s3Client, BUCKET_NAME, PUBLIC_BASE_URL, "dev/");

        s3Service.deleteFile(PUBLIC_BASE_URL + "/" + key);

        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/dev/", "../", "dev/../", "dev//", " dev/", "dev/ ", "dev\\", "dev?x=1", "dev%2f"})
    void givenInvalidPrefix__whenCreateService__thenRejectConfiguration(String prefix) {
        assertThatThrownBy(() -> new S3ServiceImpl(s3Client, BUCKET_NAME, PUBLIC_BASE_URL, prefix))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
