package com.pawbridge.communityservice.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class S3ServiceImplTest {

    private static final String BUCKET_NAME = "pawbridge-public-images";
    private static final String PUBLIC_BASE_URL = "https://images.pawbridge.kr";

    @Mock
    private S3Client s3Client;

    private S3ServiceImpl s3Service;

    @BeforeEach
    void setUp() {
        s3Service = new S3ServiceImpl(s3Client, BUCKET_NAME, PUBLIC_BASE_URL + "/");
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
}
