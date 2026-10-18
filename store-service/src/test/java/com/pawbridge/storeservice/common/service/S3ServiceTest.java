package com.pawbridge.storeservice.common.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class S3ServiceTest {

    private static final String BUCKET_NAME = "test-product-images";
    private static final String PUBLIC_BASE_URL = "https://images.pawbridge.kr/";

    @Mock
    private S3Client s3Client;

    private S3Service s3Service;

    @BeforeEach
    void setUp() {
        s3Service = new S3Service(s3Client, BUCKET_NAME, PUBLIC_BASE_URL);
    }

    @Test
    void uploadImage_returnsR2CustomDomainUrl() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "product.png",
                "image/png",
                new byte[]{1, 2, 3}
        );
        ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);

        String imageUrl = s3Service.uploadImage(file);

        verify(s3Client).putObject(requestCaptor.capture(), any(RequestBody.class));
        PutObjectRequest request = requestCaptor.getValue();
        assertThat(request.bucket()).isEqualTo(BUCKET_NAME);
        assertThat(request.key()).startsWith("products/").endsWith(".png");
        assertThat(request.contentType()).isEqualTo("image/png");
        assertThat(imageUrl).isEqualTo("https://images.pawbridge.kr/" + request.key());
    }

    @Test
    void uploadImage_rejectsNonImageFile() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "script.html",
                "text/html",
                "<script>alert(1)</script>".getBytes()
        );

        assertThatThrownBy(() -> s3Service.uploadImage(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid file type");
        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void uploadImage_rejectsImageLargerThanFiveMegabytes() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "too-large.png",
                "image/png",
                new byte[5 * 1024 * 1024 + 1]
        );

        assertThatThrownBy(() -> s3Service.uploadImage(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not exceed 5MB");
        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }
}
