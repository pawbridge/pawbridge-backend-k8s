package com.pawbridge.backend.animal.internal.image.infrastructure

import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
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
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectResponse
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import software.amazon.awssdk.services.s3.model.S3Exception
import java.io.IOException

class S3AnimalImagesTest {
    private val client = mockk<S3Client>()
    private val subject = S3AnimalImages(client, "fixture-animals", "https://assets.invalid///")

    @Test
    fun `허용된 이미지일 때__업로드하면__animals 아래 UUID v4와 MIME 확장자 키로 한 번 넣고 공개 URL을 돌려준다`() {
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
        assertThat(request.captured.key()).matches(UPLOADED_KEY)
        assertThat(request.captured.key()).endsWith(".jpg")
        assertThat(url).isEqualTo("https://assets.invalid/" + request.captured.key())
        assertThat(body.captured.contentStreamProvider().newStream().readAllBytes()).containsExactly(*bytes)
    }

    @Test
    fun `파일 이름에 확장자가 없거나 경로가 섞였을 때__업로드하면__이름을 무시하고 MIME으로 정한 확장자를 쓴다`() {
        // given
        every { client.putObject(any<PutObjectRequest>(), any<RequestBody>()) } returns PutObjectResponse.builder().build()

        // when
        val bare = subject.uploadImage(MockMultipartFile("file", "photo", "image/webp", byteArrayOf(1)))
        val tricky = subject.uploadImage(MockMultipartFile("file", "a.png/../x y%2F", "image/jpg", byteArrayOf(1)))

        // then
        assertThat(bare).matches("https://assets\\.invalid/" + UPLOADED_KEY).endsWith(".webp")
        assertThat(tricky).matches("https://assets\\.invalid/" + UPLOADED_KEY).endsWith(".jpg")
        assertThat(subject.isUploadedPhoto(bare)).isTrue()
        assertThat(subject.isUploadedPhoto(tricky)).isTrue()
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
    @ValueSource(
        strings = [
            "animals/a.png",
            "products/0b1d9a8c-3f2e-4d5a-9b6c-7e8f9a0b1c2d.png",
            "posts/images/0b1d9a8c-3f2e-4d5a-9b6c-7e8f9a0b1c2d.png",
            "animals/0b1d9a8c-3f2e-4d5a-9b6c-7e8f9a0b1c2d.JPG",
            "animals/0b1d9a8c-3f2e-4d5a-9b6c-7e8f9a0b1c2d.jpeg",
            "animals/0b1d9a8c-3f2e-4d5a-9b6c-7e8f9a0b1c2d",
            "animals/0b1d9a8c-3f2e-1d5a-9b6c-7e8f9a0b1c2d.png",
            "animals/------------------------------------.png",
            "animals/0b1d9a8c-3f2e-4d5a-9b6c-7e8f9a0b1c2d.png/../products/x.png",
            "animals%2F0b1d9a8c-3f2e-4d5a-9b6c-7e8f9a0b1c2d.png",
        ],
    )
    fun `업로드 모양이 아닌 키일 때__사진 확인과 삭제를 하면__저장소를 호출하지 않는다`(key: String) {
        // given
        val url = "https://assets.invalid/" + key

        // when
        val uploaded = subject.isUploadedPhoto(url)
        val exists = subject.exists(url)
        subject.delete(url)

        // then
        assertThat(uploaded).isFalse()
        assertThat(exists).isFalse()
        confirmVerified(client)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://other.invalid/animals/0b1d9a8c-3f2e-4d5a-9b6c-7e8f9a0b1c2d.png",
            "https://assetsXinvalid/animals/0b1d9a8c-3f2e-4d5a-9b6c-7e8f9a0b1c2d.png",
            "https://assets.invalid.evil/animals/0b1d9a8c-3f2e-4d5a-9b6c-7e8f9a0b1c2d.png",
            "",
        ],
    )
    fun `공개 기준 URL 밖의 주소일 때__사진 확인과 삭제를 하면__저장소를 호출하지 않는다`(url: String) {
        // given

        // when
        val uploaded = subject.isUploadedPhoto(url)
        subject.delete(url)

        // then
        assertThat(uploaded).isFalse()
        confirmVerified(client)
    }

    @Test
    fun `업로드한 사진 주소일 때__삭제하면__그 키 하나만 지운다`() {
        // given
        every { client.deleteObject(any<DeleteObjectRequest>()) } returns DeleteObjectResponse.builder().build()

        // when
        subject.delete("https://assets.invalid/$PHOTO_KEY")

        // then
        val request = slot<DeleteObjectRequest>()
        verify(exactly = 1) { client.deleteObject(capture(request)) }
        confirmVerified(client)
        assertThat(request.captured.bucket()).isEqualTo("fixture-animals")
        assertThat(request.captured.key()).isEqualTo(PHOTO_KEY)
    }

    @Test
    fun `저장소 삭제가 실패할 때__삭제하면__실패를 호출한 쪽에 전한다`() {
        // given
        val failure = IllegalStateException("synthetic delete failure")
        every { client.deleteObject(any<DeleteObjectRequest>()) } throws failure

        // when
        val thrown = assertThatThrownBy { subject.delete("https://assets.invalid/$PHOTO_KEY") }

        // then
        thrown.isSameAs(failure)
    }

    @Test
    fun `저장소가 있거나 404이거나 다른 오류를 줄 때__사진이 있는지 보면__참·거짓·전파로 나뉜다`() {
        // given
        val url = "https://assets.invalid/$PHOTO_KEY"
        val notFound = S3Exception.builder().statusCode(404).build()
        val unavailable = S3Exception.builder().statusCode(503).build()

        every { client.headObject(any<HeadObjectRequest>()) } returns
            HeadObjectResponse.builder().build() andThenThrows notFound andThenThrows unavailable

        // when
        val present = subject.exists(url)
        val gone = subject.exists(url)
        val failed = assertThatThrownBy { subject.exists(url) }

        // then
        assertThat(present).isTrue()
        assertThat(gone).isFalse()
        failed.isSameAs(unavailable)
        val requests = mutableListOf<HeadObjectRequest>()
        verify(exactly = 3) { client.headObject(capture(requests)) }
        assertThat(requests.map { it.bucket() to it.key() }).containsOnly("fixture-animals" to PHOTO_KEY)
    }

    private companion object {
        const val UPLOADED_KEY = "animals/[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\.(jpg|png|gif|webp)"
        const val PHOTO_KEY = "animals/0b1d9a8c-3f2e-4d5a-9b6c-7e8f9a0b1c2d.png"
    }
}
