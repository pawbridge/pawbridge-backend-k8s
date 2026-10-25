package com.pawbridge.backend.animal.internal.image.infrastructure

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.springframework.mock.web.MockMultipartFile
import org.springframework.web.multipart.MultipartFile
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.io.IOException

class S3AnimalImagesTest {
    private val client: S3Client = mock(S3Client::class.java)
    private val subject = S3AnimalImages(client, "fixture-animals", "https://assets.invalid///")

    @Test
    fun givenAllowedImage__whenUploaded__thenPutOnceUnderAnimalsAndReturnOriginalPublicUrl() {
        val bytes = byteArrayOf(1, 2, 3)
        val file = MockMultipartFile("file", "photo.JPG", "IMAGE/JPEG", bytes)

        val url = subject.uploadImage(file)

        val request = ArgumentCaptor.forClass(PutObjectRequest::class.java)
        val body = ArgumentCaptor.forClass(RequestBody::class.java)
        verify(client).putObject(request.capture(), body.capture())
        verifyNoMoreInteractions(client)
        assertThat(request.value.bucket()).isEqualTo("fixture-animals")
        assertThat(request.value.contentType()).isEqualTo("IMAGE/JPEG")
        assertThat(request.value.key()).matches("animals/[0-9a-f-]{36}\\.JPG")
        assertThat(url).isEqualTo("https://assets.invalid/" + request.value.key())
        assertThat(body.value.contentStreamProvider().newStream().readAllBytes()).containsExactly(*bytes)
    }

    @Test
    fun givenNameWithoutExtension__whenUploaded__thenUseBareUuidKey() {
        val url = subject.uploadImage(MockMultipartFile("file", "photo", "image/webp", byteArrayOf(1)))

        assertThat(url).matches("https://assets\\.invalid/animals/[0-9a-f-]{36}")
    }

    @Test
    fun givenEmptyDisallowedOrOversizedImage__whenUploaded__thenRejectWithOriginalMessagesBeforeStorage() {
        assertThatThrownBy { subject.uploadImage(null) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("파일이 비어있습니다.")
        assertThatThrownBy { subject.uploadImage(MockMultipartFile("file", "a.png", "image/png", ByteArray(0))) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("파일이 비어있습니다.")
        assertThatThrownBy { subject.uploadImage(MockMultipartFile("file", "a.txt", "text/plain", byteArrayOf(1))) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageStartingWith("허용되지 않는 파일 형식입니다. 허용: ")
        val oversized = mock(MultipartFile::class.java)
        `when`(oversized.size).thenReturn(10L * 1024 * 1024 + 1)
        `when`(oversized.contentType).thenReturn("image/png")
        assertThatThrownBy { subject.uploadImage(oversized) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("파일 크기는 10MB를 초과할 수 없습니다.")
        verifyNoInteractions(client)
    }

    @Test
    fun givenInputReadFailure__whenUploading__thenWrapIOExceptionWithoutPuttingObject() {
        val file = mock(MultipartFile::class.java)
        `when`(file.size).thenReturn(1L)
        `when`(file.contentType).thenReturn("image/png")
        `when`(file.bytes).thenThrow(IOException("synthetic read failure"))

        assertThatThrownBy { subject.uploadImage(file) }
            .isInstanceOf(RuntimeException::class.java)
            .hasMessage("이미지 업로드에 실패했습니다.")
            .hasCauseInstanceOf(IOException::class.java)
        verifyNoInteractions(client)
    }

    @Test
    fun givenUploadedBody__whenSdkRequestsTheStreamMoreThanOnce__thenEveryStreamHasTheFullImage() {
        val bytes = byteArrayOf(1, 2, 3)

        subject.uploadImage(MockMultipartFile("file", "photo.png", "image/png", bytes))

        val body = ArgumentCaptor.forClass(RequestBody::class.java)
        verify(client).putObject(any(PutObjectRequest::class.java), body.capture())
        assertThat(body.value.contentLength()).isEqualTo(bytes.size.toLong())
        assertThat(body.value.contentStreamProvider().newStream().readAllBytes()).containsExactly(*bytes)
        assertThat(body.value.contentStreamProvider().newStream().readAllBytes()).containsExactly(*bytes)
    }

    @Test
    fun givenStorageFailure__whenUploading__thenPropagateWithoutRetryOrCompensation() {
        val failure = IllegalStateException("synthetic storage failure")
        `when`(client.putObject(any(PutObjectRequest::class.java), any(RequestBody::class.java))).thenThrow(failure)

        assertThatThrownBy {
            subject.uploadImage(MockMultipartFile("file", "a.png", "image/png", byteArrayOf(1)))
        }.isSameAs(failure)

        verify(client, times(1)).putObject(any(PutObjectRequest::class.java), any(RequestBody::class.java))
        verifyNoMoreInteractions(client)
    }

    @ParameterizedTest
    @ValueSource(strings = ["animals/a.png", "products/b.png", "posts/c/d.png"])
    fun givenAnyKeyUnderPublicBase__whenDeleted__thenDeleteThatKeyWithoutFolderOrOwnerCheck(key: String) {
        subject.deleteImage("https://assets.invalid/" + key)

        val request = ArgumentCaptor.forClass(DeleteObjectRequest::class.java)
        verify(client).deleteObject(request.capture())
        verifyNoMoreInteractions(client)
        assertThat(request.value.bucket()).isEqualTo("fixture-animals")
        assertThat(request.value.key()).isEqualTo(key)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "  ", "https://other.invalid/animals/a.png", "https://assets.invalid/"])
    fun givenBlankForeignOrKeylessUrl__whenDeleted__thenSilentlyIgnoreWithoutStorage(url: String) {
        assertThatCode { subject.deleteImage(url) }.doesNotThrowAnyException()
        assertThatCode { subject.deleteImage(null) }.doesNotThrowAnyException()
        verifyNoInteractions(client)
    }

    @Test
    fun givenStorageDeleteFailure__whenDeleted__thenSwallowTheFailure() {
        `when`(client.deleteObject(any(DeleteObjectRequest::class.java)))
            .thenThrow(IllegalStateException("synthetic delete failure"))

        assertThatCode { subject.deleteImage("https://assets.invalid/animals/a.png") }.doesNotThrowAnyException()

        verify(client, times(1)).deleteObject(any(DeleteObjectRequest::class.java))
        verifyNoMoreInteractions(client)
    }
}
