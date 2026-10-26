package com.pawbridge.backend.store.internal.catalogwrite.infrastructure

import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockMultipartFile
import org.springframework.web.multipart.MultipartFile
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import java.io.IOException

class S3ProductImagesTest {
    private val client = mockk<S3Client>()
    private val subject = S3ProductImages(client, "fixture-products", "https://assets.invalid///")

    @Test
    fun `허용된 이미지일 때__업로드하면__products 아래에 한 번 넣고 원래의 공개 URL을 돌려준다`() {
        // given
        val bytes = byteArrayOf(1, 2, 3)
        val file = MockMultipartFile("file", "photo.png", "image/png", bytes)

        every { client.putObject(any<PutObjectRequest>(), any<RequestBody>()) } returns PutObjectResponse.builder().build()

        // when
        val url = subject.uploadImage(file)

        // then
        val request = slot<PutObjectRequest>()
        val body = slot<RequestBody>()
        verify(exactly = 1) { client.putObject(capture(request), capture(body)) }
        confirmVerified(client)
        assertThat(request.captured.bucket()).isEqualTo("fixture-products")
        assertThat(request.captured.contentType()).isEqualTo("image/png")
        assertThat(request.captured.key()).matches("products/[0-9a-f-]{36}\\.png")
        assertThat(url).isEqualTo("https://assets.invalid/" + request.captured.key())
        assertThat(body.captured.contentStreamProvider().newStream().readAllBytes()).containsExactly(*bytes)
    }

    @Test
    fun `비었거나 너무 크거나 허용되지 않은 이미지일 때__업로드하면__R2 호출 전에 거부한다`() {
        // given
        val oversized = mockk<MultipartFile>()

        every { oversized.isEmpty } returns false
        every { oversized.size } returns 5L * 1024 * 1024 + 1

        // when
        val emptyThrown = assertThatThrownBy {
            subject.uploadImage(MockMultipartFile("file", "a.png", "image/png", ByteArray(0)))
        }
        val oversizedThrown = assertThatThrownBy { subject.uploadImage(oversized) }
        val disallowedThrown = assertThatThrownBy {
            subject.uploadImage(MockMultipartFile("file", "a.txt", "text/plain", byteArrayOf(1)))
        }

        // then
        emptyThrown.isInstanceOf(IllegalArgumentException::class.java)
        oversizedThrown.isInstanceOf(IllegalArgumentException::class.java)
        disallowedThrown.isInstanceOf(IllegalArgumentException::class.java)
        confirmVerified(client)
    }

    @Test
    fun `입력 읽기가 실패할 때__업로드하면__객체를 넣지 않고 IOException을 감싼다`() {
        // given
        val file = mockk<MultipartFile>()

        every { file.isEmpty } returns false
        every { file.size } returns 1L
        every { file.contentType } returns "image/png"
        every { file.originalFilename } returns null
        every { file.bytes } throws IOException("synthetic read failure")

        // when
        val thrown = assertThatThrownBy { subject.uploadImage(file) }

        // then
        thrown.isInstanceOf(RuntimeException::class.java)
            .hasMessage("Failed to upload image")
            .hasCauseInstanceOf(IOException::class.java)
        confirmVerified(client)
    }

    @Test
    fun `업로드된 본문일 때__SDK가 스트림을 여러 번 요청하면__모든 스트림이 전체 이미지를 담는다`() {
        // given
        val bytes = byteArrayOf(1, 2, 3)

        every { client.putObject(any<PutObjectRequest>(), any<RequestBody>()) } returns PutObjectResponse.builder().build()

        // when
        subject.uploadImage(MockMultipartFile("file", "photo.png", "image/png", bytes))

        // then
        val body = slot<RequestBody>()
        verify(exactly = 1) { client.putObject(any<PutObjectRequest>(), capture(body)) }
        assertThat(body.captured.contentLength()).isEqualTo(bytes.size.toLong())
        assertThat(body.captured.contentStreamProvider().newStream().readAllBytes()).containsExactly(*bytes)
        assertThat(body.captured.contentStreamProvider().newStream().readAllBytes()).containsExactly(*bytes)
    }

    @Test
    fun `R2가 실패할 때__업로드하면__재시도나 보상 없이 전파한다`() {
        // given
        val failure = IllegalStateException("synthetic R2 failure")

        every { client.putObject(any<PutObjectRequest>(), any<RequestBody>()) } throws failure

        // when
        val thrown = assertThatThrownBy {
            subject.uploadImage(MockMultipartFile("file", "a.png", "image/png", byteArrayOf(1)))
        }

        // then
        thrown.isSameAs(failure)
        verify(exactly = 1) { client.putObject(any<PutObjectRequest>(), any<RequestBody>()) }
        confirmVerified(client)
    }
}
