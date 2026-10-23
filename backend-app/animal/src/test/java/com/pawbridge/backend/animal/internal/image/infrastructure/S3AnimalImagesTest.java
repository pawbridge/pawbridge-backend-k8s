package com.pawbridge.backend.animal.internal.image.infrastructure;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class S3AnimalImagesTest {
    private final S3Client client=mock(S3Client.class);
    private final S3AnimalImages subject=new S3AnimalImages(client,"fixture-animals","https://assets.invalid///");

    @Test void givenAllowedImage__whenUploaded__thenPutOnceUnderAnimalsAndReturnOriginalPublicUrl() throws Exception {
        byte[] bytes={1,2,3};var file=new MockMultipartFile("file","photo.JPG","IMAGE/JPEG",bytes);
        String url=subject.uploadImage(file);var request=ArgumentCaptor.forClass(PutObjectRequest.class);var body=ArgumentCaptor.forClass(RequestBody.class);
        verify(client).putObject(request.capture(),body.capture());verifyNoMoreInteractions(client);
        assertThat(request.getValue().bucket()).isEqualTo("fixture-animals");assertThat(request.getValue().contentType()).isEqualTo("IMAGE/JPEG");
        assertThat(request.getValue().key()).matches("animals/[0-9a-f-]{36}\\.JPG");assertThat(url).isEqualTo("https://assets.invalid/"+request.getValue().key());
        assertThat(body.getValue().contentStreamProvider().newStream().readAllBytes()).containsExactly(bytes);
    }

    @Test void givenNameWithoutExtension__whenUploaded__thenUseBareUuidKey() {
        String url=subject.uploadImage(new MockMultipartFile("file","photo","image/webp",new byte[]{1}));
        assertThat(url).matches("https://assets\\.invalid/animals/[0-9a-f-]{36}");
    }

    @Test void givenEmptyDisallowedOrOversizedImage__whenUploaded__thenRejectWithOriginalMessagesBeforeStorage() {
        assertThatThrownBy(()->subject.uploadImage(null)).isInstanceOf(IllegalArgumentException.class).hasMessage("파일이 비어있습니다.");
        assertThatThrownBy(()->subject.uploadImage(new MockMultipartFile("file","a.png","image/png",new byte[0])))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("파일이 비어있습니다.");
        assertThatThrownBy(()->subject.uploadImage(new MockMultipartFile("file","a.txt","text/plain",new byte[]{1})))
                .isInstanceOf(IllegalArgumentException.class).hasMessageStartingWith("허용되지 않는 파일 형식입니다. 허용: ");
        MultipartFile oversized=mock(MultipartFile.class);when(oversized.getSize()).thenReturn(10L*1024*1024+1);when(oversized.getContentType()).thenReturn("image/png");
        assertThatThrownBy(()->subject.uploadImage(oversized)).isInstanceOf(IllegalArgumentException.class).hasMessage("파일 크기는 10MB를 초과할 수 없습니다.");
        verifyNoInteractions(client);
    }

    @Test void givenInputReadFailure__whenUploading__thenWrapIOExceptionWithoutPuttingObject() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn(1L);
        when(file.getContentType()).thenReturn("image/png");
        when(file.getBytes()).thenThrow(new IOException("synthetic read failure"));

        assertThatThrownBy(() -> subject.uploadImage(file))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("이미지 업로드에 실패했습니다.")
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

    @Test void givenStorageFailure__whenUploading__thenPropagateWithoutRetryOrCompensation() {
        var failure=new IllegalStateException("synthetic storage failure");when(client.putObject(any(PutObjectRequest.class),any(RequestBody.class))).thenThrow(failure);
        assertThatThrownBy(()->subject.uploadImage(new MockMultipartFile("file","a.png","image/png",new byte[]{1}))).isSameAs(failure);
        verify(client,times(1)).putObject(any(PutObjectRequest.class),any(RequestBody.class));verifyNoMoreInteractions(client);
    }

    @ParameterizedTest @ValueSource(strings={"animals/a.png","products/b.png","posts/c/d.png"})
    void givenAnyKeyUnderPublicBase__whenDeleted__thenDeleteThatKeyWithoutFolderOrOwnerCheck(String key) {
        subject.deleteImage("https://assets.invalid/"+key);
        var request=ArgumentCaptor.forClass(DeleteObjectRequest.class);verify(client).deleteObject(request.capture());verifyNoMoreInteractions(client);
        assertThat(request.getValue().bucket()).isEqualTo("fixture-animals");assertThat(request.getValue().key()).isEqualTo(key);
    }

    @ParameterizedTest @ValueSource(strings={"","  ","https://other.invalid/animals/a.png","https://assets.invalid/"})
    void givenBlankForeignOrKeylessUrl__whenDeleted__thenSilentlyIgnoreWithoutStorage(String url) {
        assertThatCode(()->subject.deleteImage(url)).doesNotThrowAnyException();
        assertThatCode(()->subject.deleteImage(null)).doesNotThrowAnyException();
        verifyNoInteractions(client);
    }

    @Test void givenStorageDeleteFailure__whenDeleted__thenSwallowTheFailure() {
        when(client.deleteObject(any(DeleteObjectRequest.class))).thenThrow(new IllegalStateException("synthetic delete failure"));
        assertThatCode(()->subject.deleteImage("https://assets.invalid/animals/a.png")).doesNotThrowAnyException();
        verify(client,times(1)).deleteObject(any(DeleteObjectRequest.class));verifyNoMoreInteractions(client);
    }
}
