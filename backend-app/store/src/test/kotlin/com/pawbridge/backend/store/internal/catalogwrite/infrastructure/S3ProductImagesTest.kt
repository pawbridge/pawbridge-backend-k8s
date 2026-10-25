package com.pawbridge.backend.store.internal.catalogwrite.infrastructure

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.springframework.mock.web.MockMultipartFile
import org.springframework.web.multipart.MultipartFile
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.io.IOException

class S3ProductImagesTest {
    private val client: S3Client = mock(S3Client::class.java)
    private val subject = S3ProductImages(client, "fixture-products", "https://assets.invalid///")

    @Test
    fun givenAllowedImage__whenUploaded__thenPutOnceUnderProductsAndReturnOriginalPublicUrl() {
        val bytes = byteArrayOf(1, 2, 3)
        val file = MockMultipartFile("file", "photo.png", "image/png", bytes)

        val url = subject.uploadImage(file)
        val request = ArgumentCaptor.forClass(PutObjectRequest::class.java)
        val body = ArgumentCaptor.forClass(RequestBody::class.java)

        verify(client).putObject(request.capture(), body.capture())
        verifyNoMoreInteractions(client)
        assertThat(request.value.bucket()).isEqualTo("fixture-products")
        assertThat(request.value.contentType()).isEqualTo("image/png")
        assertThat(request.value.key()).matches("products/[0-9a-f-]{36}\\.png")
        assertThat(url).isEqualTo("https://assets.invalid/" + request.value.key())
        assertThat(body.value.contentStreamProvider().newStream().readAllBytes()).containsExactly(*bytes)
    }

    @Test
    fun givenEmptyOversizedOrDisallowedImage__whenUploaded__thenRejectBeforeR2() {
        assertThatThrownBy {
            subject.uploadImage(MockMultipartFile("file", "a.png", "image/png", ByteArray(0)))
        }.isInstanceOf(IllegalArgumentException::class.java)
        val oversized = mock(MultipartFile::class.java)
        `when`(oversized.size).thenReturn(5L * 1024 * 1024 + 1)
        assertThatThrownBy { subject.uploadImage(oversized) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            subject.uploadImage(MockMultipartFile("file", "a.txt", "text/plain", byteArrayOf(1)))
        }.isInstanceOf(IllegalArgumentException::class.java)
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
            .hasMessage("Failed to upload image")
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
    fun givenR2Failure__whenUploading__thenPropagateWithoutRetryOrCompensation() {
        val failure = IllegalStateException("synthetic R2 failure")
        `when`(client.putObject(any(PutObjectRequest::class.java), any(RequestBody::class.java))).thenThrow(failure)

        assertThatThrownBy {
            subject.uploadImage(MockMultipartFile("file", "a.png", "image/png", byteArrayOf(1)))
        }.isSameAs(failure)

        verify(client, times(1)).putObject(any(PutObjectRequest::class.java), any(RequestBody::class.java))
        verifyNoMoreInteractions(client)
    }
}
