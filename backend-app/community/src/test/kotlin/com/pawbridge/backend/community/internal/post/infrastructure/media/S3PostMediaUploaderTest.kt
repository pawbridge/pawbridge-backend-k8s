package com.pawbridge.backend.community.internal.post.infrastructure.media

import com.pawbridge.backend.community.internal.post.application.PostUpload
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.UncheckedIOException
import java.util.stream.Stream

class S3PostMediaUploaderTest {
    private val s3 = mockk<S3Client>()
    private val uploader = S3PostMediaUploader(s3, BUCKET, "$PUBLIC_BASE///", "")

    @Test
    fun `첨부가 없거나 크기가 0일 때__업로드하면__저장소를 호출하지 않고 내용을 열지 않는다`() {
        // given
        val empty = PostUpload(
            "empty.txt",
            "text/plain",
            0,
            PostUpload.Content { throw AssertionError("Empty attachments must not be opened") },
        )

        // when
        val fromNull = uploader.upload(null)
        val fromEmptyList = uploader.upload(listOf())
        val fromZeroSize = uploader.upload(listOf(empty))

        // then
        assertTrue(fromNull.isEmpty())
        assertTrue(fromEmptyList.isEmpty())
        assertTrue(fromZeroSize.isEmpty())
        confirmVerified(s3)
    }

    @ParameterizedTest
    @MethodSource("allowedMedia")
    fun `허용된 미디어일 때__업로드하면__바이트와 MIME과 폴더와 공개 URL이 기존과 같다`(
        mime: String,
        folder: String,
    ) {
        // given
        val bytes = byteArrayOf(0, 7, 255.toByte(), 11)
        val stream = TrackingStream(bytes)

        every { s3.putObject(any<PutObjectRequest>(), any<RequestBody>()) } answers {
            val body = secondArg<RequestBody>()
            assertEquals(bytes.size.toLong(), body.optionalContentLength().orElseThrow().toLong())
            assertArrayEquals(bytes, body.contentStreamProvider().newStream().readAllBytes())
            assertFalse(stream.closed)
            PutObjectResponse.builder().build()
        }

        // when
        val urls = uploader.upload(
            listOf(PostUpload("pet.Photo.PNG", mime, bytes.size.toLong(), PostUpload.Content { stream })),
        )

        // then
        val captured = slot<PutObjectRequest>()
        verify(exactly = 1) { s3.putObject(capture(captured), any<RequestBody>()) }
        val request = captured.captured
        assertEquals(BUCKET, request.bucket())
        assertEquals(mime, request.contentType())
        assertTrue(request.key().matches(Regex(folder + "[0-9a-f-]{36}\\.PNG")))
        assertEquals(listOf(PUBLIC_BASE + "/" + request.key()), urls)
        assertTrue(stream.closed)
    }

    @ParameterizedTest
    @ValueSource(strings = ["f3-owner", "f3-owner/", "f3-owner/nested", "f3-owner/nested/"])
    fun `접두사가 설정됐을 때__업로드하면__정규화된 접두사와 첨부 순서를 보존한다`(
        prefix: String,
    ) {
        // given
        val prefixed = S3PostMediaUploader(s3, BUCKET, PUBLIC_BASE, prefix)

        every { s3.putObject(any<PutObjectRequest>(), any<RequestBody>()) } returns PutObjectResponse.builder().build()

        // when
        val urls = prefixed.upload(
            listOf(
                upload("first.png", "image/png", byteArrayOf(1)),
                upload("empty", null, byteArrayOf()),
                upload("second.mp4", "video/mp4", byteArrayOf(2)),
            ),
        )

        // then
        val requests = mutableListOf<PutObjectRequest>()
        verify(exactly = 2) { s3.putObject(capture(requests), any<RequestBody>()) }
        val normalized = if (prefix.endsWith("/")) prefix else "$prefix/"
        assertEquals(2, requests.size)
        assertTrue(requests[0].key().startsWith(normalized + "posts/images/"))
        assertTrue(requests[1].key().startsWith(normalized + "posts/videos/"))
        assertEquals(requests.map { request -> PUBLIC_BASE + "/" + request.key() }, urls)
    }

    @ParameterizedTest
    @MethodSource("filenames")
    fun `파일 이름이 주어질 때__업로드하면__마지막 확장자만 보존한다`(filename: String?, extension: String) {
        // given
        every { s3.putObject(any<PutObjectRequest>(), any<RequestBody>()) } returns PutObjectResponse.builder().build()

        // when
        uploader.upload(listOf(upload(filename, "image/png", byteArrayOf(1))))

        // then
        val captured = slot<PutObjectRequest>()
        verify(exactly = 1) { s3.putObject(capture(captured), any<RequestBody>()) }
        assertTrue(captured.captured.key().matches(Regex("posts/images/[0-9a-f-]{36}$extension")))
    }

    @ParameterizedTest
    @MethodSource("unsupportedMedia")
    fun `지원하지 않는 미디어일 때__업로드하면__내용을 열기 전에 기존 실패를 감싸 던진다`(
        contentType: String?,
        expectedMessage: String,
    ) {
        // given
        val upload = PostUpload(
            "pet.txt",
            contentType,
            1,
            PostUpload.Content { throw AssertionError("Rejected media must not be opened") },
        )

        // when
        val failure = assertThrows(RuntimeException::class.java) { uploader.upload(listOf(upload)) }

        // then
        assertEquals("파일 업로드 중 오류가 발생했습니다", failure.message)
        assertInstanceOf(IllegalArgumentException::class.java, failure.cause)
        assertEquals(expectedMessage, failure.cause!!.message)
        confirmVerified(s3)
    }

    @Test
    fun `내용을 여는 데 실패할 때__업로드하면__저장소 호출 없이 실패를 감싸 던진다`() {
        // given
        val cause = IOException("synthetic read failure")
        val upload = PostUpload("pet.png", "image/png", 1, PostUpload.Content { throw cause })

        // when
        val failure = assertThrows(RuntimeException::class.java) { uploader.upload(listOf(upload)) }

        // then
        assertEquals("파일 업로드 중 오류가 발생했습니다", failure.message)
        assertSame(cause, failure.cause)
        confirmVerified(s3)
    }

    @Test
    fun `두 번째 저장이 실패할 때__업로드하면__스트림을 닫고 정리나 이후 저장을 하지 않는다`() {
        // given
        val first = TrackingStream(byteArrayOf(1))
        val second = TrackingStream(byteArrayOf(2))
        val cause = IllegalStateException("synthetic storage failure")
        val third = PostUpload(
            "third.png",
            "image/png",
            1,
            PostUpload.Content { throw AssertionError("No later attachment may open after a failure") },
        )

        every { s3.putObject(any<PutObjectRequest>(), any<RequestBody>()) } returns
            PutObjectResponse.builder().build() andThenThrows cause

        // when
        val failure = assertThrows(RuntimeException::class.java) {
            uploader.upload(
                listOf(
                    PostUpload("first.png", "image/png", 1, PostUpload.Content { first }),
                    PostUpload("second.png", "image/png", 1, PostUpload.Content { second }),
                    third,
                ),
            )
        }

        // then
        assertEquals("파일 업로드 중 오류가 발생했습니다", failure.message)
        assertSame(cause, failure.cause)
        assertTrue(first.closed)
        assertTrue(second.closed)
        verify(exactly = 2) { s3.putObject(any<PutObjectRequest>(), any<RequestBody>()) }
        confirmVerified(s3)
    }

    @ParameterizedTest
    @ValueSource(ints = [8, 200_000])
    fun `다시 감을 수 없는 원본일 때__SDK가 서명과 전송을 위해 읽으면__매번 새 바이트를 주고 스트림을 닫는다`(
        length: Int,
    ) {
        // given
        val bytes = ByteArray(length)
        bytes.fill(7)
        val opened = ArrayList<TrackingStream>()
        val upload = PostUpload(
            "disk.png",
            "image/png",
            length.toLong(),
            PostUpload.Content {
                val stream = TrackingStream(bytes)
                opened.add(stream)
                object : FilterInputStream(stream) {
                    override fun markSupported(): Boolean = false

                    override fun reset() {
                        throw IOException("non-resettable disk source")
                    }
                }
            },
        )

        every { s3.putObject(any<PutObjectRequest>(), any<RequestBody>()) } answers {
            val body = secondArg<RequestBody>()
            val signing: InputStream = body.contentStreamProvider().newStream()
            assertFalse(signing.markSupported())
            assertArrayEquals(bytes, signing.readAllBytes())
            val sending: InputStream = body.contentStreamProvider().newStream()
            assertArrayEquals(bytes, sending.readAllBytes())
            PutObjectResponse.builder().build()
        }

        // when
        uploader.upload(listOf(upload))

        // then
        assertEquals(2, opened.size)
        assertTrue(opened.all { stream -> stream.closed })
        verify(exactly = 1) { s3.putObject(any<PutObjectRequest>(), any<RequestBody>()) }
    }

    @Test
    fun `원본을 다시 여는 데 실패할 때__SDK가 한 번 더 읽으려 하면__이전 스트림을 닫고 업로드를 실패시킨다`() {
        // given
        val first = TrackingStream(byteArrayOf(1))
        val cause = IOException("synthetic reopen failure")
        var opens = 0
        val upload = PostUpload(
            "disk.png",
            "image/png",
            1,
            PostUpload.Content {
                if (opens++ == 0) {
                    first
                } else {
                    throw cause
                }
            },
        )

        every { s3.putObject(any<PutObjectRequest>(), any<RequestBody>()) } answers {
            val body = secondArg<RequestBody>()
            body.contentStreamProvider().newStream().readAllBytes()
            body.contentStreamProvider().newStream()
            PutObjectResponse.builder().build()
        }

        // when
        val failure = assertThrows(RuntimeException::class.java) { uploader.upload(listOf(upload)) }

        // then
        assertSame(cause, assertInstanceOf(UncheckedIOException::class.java, failure.cause).cause)
        assertTrue(first.closed)
        verify(exactly = 1) { s3.putObject(any<PutObjectRequest>(), any<RequestBody>()) }
        confirmVerified(s3)
    }

    @ParameterizedTest
    @ValueSource(strings = ["/f3/", "../", "f3/../", "f3//", " f3/", "f3/ ", "f3\\", "f3?x=1", "f3%2f"])
    fun `잘못된 접두사일 때__생성하면__기존 설정 검증이 거부한다`(prefix: String) {
        // given
        val invalidPrefix = prefix

        // when
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            S3PostMediaUploader(s3, BUCKET, PUBLIC_BASE, invalidPrefix)
        }

        // then
        assertEquals(IllegalArgumentException::class.java, thrown.javaClass)
        confirmVerified(s3)
    }

    private class TrackingStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false

        override fun close() {
            closed = true
            super.close()
        }
    }

    companion object {
        private const val BUCKET = "f3-synthetic-bucket"
        private const val PUBLIC_BASE = "https://media.example.test"

        private fun upload(filename: String?, mime: String?, bytes: ByteArray): PostUpload =
            PostUpload(filename, mime, bytes.size.toLong(), PostUpload.Content { ByteArrayInputStream(bytes) })

        @JvmStatic
        fun allowedMedia(): Stream<Arguments> =
            Stream.of(
                Arguments.of("image/jpeg", "posts/images/"),
                Arguments.of("image/jpg", "posts/images/"),
                Arguments.of("image/png", "posts/images/"),
                Arguments.of("image/gif", "posts/images/"),
                Arguments.of("image/webp", "posts/images/"),
                Arguments.of("video/mp4", "posts/videos/"),
                Arguments.of("video/quicktime", "posts/videos/"),
                Arguments.of("video/x-msvideo", "posts/videos/"),
                Arguments.of("video/x-matroska", "posts/videos/"),
                Arguments.of("IMAGE/PNG", "posts/images/"),
                Arguments.of("VIDEO/MP4", "posts/videos/"),
            )

        @JvmStatic
        fun filenames(): Stream<Arguments> =
            Stream.of(
                Arguments.of(null, ""),
                Arguments.of("no-extension", ""),
                Arguments.of("image.tar.GZ", "\\.GZ"),
                Arguments.of("image.", "\\."),
            )

        @JvmStatic
        fun unsupportedMedia(): Stream<Arguments> =
            Stream.of(
                Arguments.of(null, "파일 타입을 확인할 수 없습니다. (파일: pet.txt)"),
                Arguments.of(
                    "text/plain",
                    "지원하지 않는 파일 형식입니다. (업로드한 파일: pet.txt, 타입: text/plain). " +
                        "허용된 형식 - 이미지: jpg, jpeg, png, gif, webp / 영상: mp4, mov, avi, mkv",
                ),
                Arguments.of(
                    "image/svg+xml",
                    "지원하지 않는 파일 형식입니다. (업로드한 파일: pet.txt, 타입: image/svg+xml). " +
                        "허용된 형식 - 이미지: jpg, jpeg, png, gif, webp / 영상: mp4, mov, avi, mkv",
                ),
            )
    }
}
