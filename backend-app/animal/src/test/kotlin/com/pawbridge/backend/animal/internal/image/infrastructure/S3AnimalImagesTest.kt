package com.pawbridge.backend.animal.internal.image.infrastructure

import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.mock.web.MockMultipartFile
import org.springframework.web.multipart.MultipartFile
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import java.io.IOException

class S3AnimalImagesTest {
    private val client = mockk<S3Client>()
    private val subject = S3AnimalImages(client, "fixture-animals", "https://assets.invalid///")

    @Test
    fun `허용된 이미지일 때__업로드하면__animals 아래에 한 번 넣고 원래의 공개 URL을 돌려준다`() {
        // given
        val bytes = byteArrayOf(1, 2, 3)
        val file = MockMultipartFile("file", "photo.JPG", "IMAGE/JPEG", bytes)

        every { client.putObject(any<PutObjectRequest>(), any<RequestBody>()) } returns PutObjectResponse.builder().build()

        // when
        val url = subject.uploadImage(file)

        // then
        val request = slot<PutObjectRequest>()
        val body = slot<RequestBody>()
        verify(exactly = 1) { client.putObject(capture(request), capture(body)) }
        confirmVerified(client)
        assertThat(request.captured.bucket()).isEqualTo("fixture-animals")
        assertThat(request.captured.contentType()).isEqualTo("IMAGE/JPEG")
        assertThat(request.captured.key()).matches("animals/[0-9a-f-]{36}\\.JPG")
        assertThat(url).isEqualTo("https://assets.invalid/" + request.captured.key())
        assertThat(body.captured.contentStreamProvider().newStream().readAllBytes()).containsExactly(*bytes)
    }

    @Test
    fun `확장자 없는 이름일 때__업로드하면__UUID만 있는 키를 쓴다`() {
        // given
        every { client.putObject(any<PutObjectRequest>(), any<RequestBody>()) } returns PutObjectResponse.builder().build()

        // when
        val url = subject.uploadImage(MockMultipartFile("file", "photo", "image/webp", byteArrayOf(1)))

        // then
        assertThat(url).matches("https://assets\\.invalid/animals/[0-9a-f-]{36}")
    }

    @Test
    fun `비었거나 허용되지 않았거나 너무 큰 이미지일 때__업로드하면__저장 전에 원래 메시지로 거부한다`() {
        // given
        val oversized = mockk<MultipartFile>()

        every { oversized.isEmpty } returns false
        every { oversized.size } returns 10L * 1024 * 1024 + 1
        every { oversized.contentType } returns "image/png"

        // when
        val missing = assertThatThrownBy { subject.uploadImage(null) }
        val empty = assertThatThrownBy {
            subject.uploadImage(MockMultipartFile("file", "a.png", "image/png", ByteArray(0)))
        }
        val disallowed = assertThatThrownBy {
            subject.uploadImage(MockMultipartFile("file", "a.txt", "text/plain", byteArrayOf(1)))
        }
        val tooLarge = assertThatThrownBy { subject.uploadImage(oversized) }

        // then
        missing.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("파일이 비어있습니다.")
        empty.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("파일이 비어있습니다.")
        disallowed.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageStartingWith("허용되지 않는 파일 형식입니다. 허용: ")
        tooLarge.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("파일 크기는 10MB를 초과할 수 없습니다.")
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
            .hasMessage("이미지 업로드에 실패했습니다.")
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
    fun `저장소가 실패할 때__업로드하면__재시도나 보상 없이 전파한다`() {
        // given
        val failure = IllegalStateException("synthetic storage failure")

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

    @ParameterizedTest
    @ValueSource(strings = ["animals/a.png", "products/b.png", "posts/c/d.png"])
    fun `공개 기준 URL 아래의 아무 키일 때__삭제하면__폴더나 소유자 확인 없이 그 키를 삭제한다`(key: String) {
        // given
        every { client.deleteObject(any<DeleteObjectRequest>()) } returns DeleteObjectResponse.builder().build()

        // when
        subject.deleteImage("https://assets.invalid/" + key)

        // then
        val request = slot<DeleteObjectRequest>()
        verify(exactly = 1) { client.deleteObject(capture(request)) }
        confirmVerified(client)
        assertThat(request.captured.bucket()).isEqualTo("fixture-animals")
        assertThat(request.captured.key()).isEqualTo(key)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "  ", "https://other.invalid/animals/a.png", "https://assets.invalid/"])
    fun `비었거나 외부이거나 키 없는 URL일 때__삭제하면__저장소 호출 없이 조용히 무시한다`(url: String) {
        // given

        // when
        val blankOrForeign = assertThatCode { subject.deleteImage(url) }
        val missing = assertThatCode { subject.deleteImage(null) }

        // then
        blankOrForeign.doesNotThrowAnyException()
        missing.doesNotThrowAnyException()
        confirmVerified(client)
    }

    @Test
    fun `저장소 삭제가 실패할 때__삭제하면__실패를 삼킨다`() {
        // given
        every { client.deleteObject(any<DeleteObjectRequest>()) } throws IllegalStateException("synthetic delete failure")

        // when
        val deletion = assertThatCode { subject.deleteImage("https://assets.invalid/animals/a.png") }

        // then
        deletion.doesNotThrowAnyException()
        verify(exactly = 1) { client.deleteObject(any<DeleteObjectRequest>()) }
        confirmVerified(client)
    }
}
