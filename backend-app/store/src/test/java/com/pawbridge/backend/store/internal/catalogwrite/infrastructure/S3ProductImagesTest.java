package com.pawbridge.backend.store.internal.catalogwrite.infrastructure;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class S3ProductImagesTest {
    private final S3Client client=mock(S3Client.class);
    private final S3ProductImages subject=new S3ProductImages(client,"fixture-products","https://assets.invalid///");

    @Test void givenAllowedImage__whenUploaded__thenPutOnceUnderProductsAndReturnOriginalPublicUrl() throws Exception {
        byte[] bytes={1,2,3};var file=new MockMultipartFile("file","photo.png","image/png",bytes);
        String url=subject.uploadImage(file);var request=ArgumentCaptor.forClass(PutObjectRequest.class);var body=ArgumentCaptor.forClass(RequestBody.class);
        verify(client).putObject(request.capture(),body.capture());verifyNoMoreInteractions(client);
        assertThat(request.getValue().bucket()).isEqualTo("fixture-products");assertThat(request.getValue().contentType()).isEqualTo("image/png");
        assertThat(request.getValue().key()).matches("products/[0-9a-f-]{36}\\.png");assertThat(url).isEqualTo("https://assets.invalid/"+request.getValue().key());
        assertThat(body.getValue().contentStreamProvider().newStream().readAllBytes()).containsExactly(bytes);
    }

    @Test void givenEmptyOversizedOrDisallowedImage__whenUploaded__thenRejectBeforeR2() {
        assertThatThrownBy(()->subject.uploadImage(new MockMultipartFile("file","a.png","image/png",new byte[0]))).isInstanceOf(IllegalArgumentException.class);
        MultipartFile oversized=mock(MultipartFile.class);when(oversized.getSize()).thenReturn(5L*1024*1024+1);
        assertThatThrownBy(()->subject.uploadImage(oversized)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->subject.uploadImage(new MockMultipartFile("file","a.txt","text/plain",new byte[]{1}))).isInstanceOf(IllegalArgumentException.class);verifyNoInteractions(client);
    }

    @Test void givenInputReadFailure__whenUploading__thenWrapIOExceptionWithoutPuttingObject() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn(1L);
        when(file.getContentType()).thenReturn("image/png");
        when(file.getBytes()).thenThrow(new IOException("synthetic read failure"));

        assertThatThrownBy(() -> subject.uploadImage(file))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Failed to upload image")
                .hasCauseInstanceOf(IOException.class);
        verifyNoInteractions(client);
    }

    @Test void givenUploadedBody__whenSdkRequestsTheStreamMoreThanOnce__thenEveryStreamHasTheFullImage() throws Exception {
        byte[] bytes = {1, 2, 3};
        subject.uploadImage(new MockMultipartFile("file", "photo.png", "image/png", bytes));
        var body = ArgumentCaptor.forClass(RequestBody.class);
        verify(client).putObject(any(PutObjectRequest.class), body.capture());

        assertThat(body.getValue().contentLength()).isEqualTo(bytes.length);
        assertThat(body.getValue().contentStreamProvider().newStream().readAllBytes()).containsExactly(bytes);
        assertThat(body.getValue().contentStreamProvider().newStream().readAllBytes()).containsExactly(bytes);
    }

    @Test void givenR2Failure__whenUploading__thenPropagateWithoutRetryOrCompensation() {
        var failure=new IllegalStateException("synthetic R2 failure");when(client.putObject(any(PutObjectRequest.class),any(RequestBody.class))).thenThrow(failure);
        assertThatThrownBy(()->subject.uploadImage(new MockMultipartFile("file","a.png","image/png",new byte[]{1}))).isSameAs(failure);
        verify(client,times(1)).putObject(any(PutObjectRequest.class),any(RequestBody.class));verifyNoMoreInteractions(client);
    }
}
