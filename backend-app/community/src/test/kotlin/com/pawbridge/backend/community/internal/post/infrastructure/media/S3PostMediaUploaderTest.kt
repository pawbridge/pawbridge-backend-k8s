package com.pawbridge.backend.community.internal.post.infrastructure.media

import com.pawbridge.backend.community.internal.post.application.PostUpload
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
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when` as whenever
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.UncheckedIOException
import java.util.stream.Stream

class S3PostMediaUploaderTest {
    private val s3 = mock(S3Client::class.java)
    private val uploader = S3PostMediaUploader(s3, BUCKET, "$PUBLIC_BASE///", "")

    @Test
    fun given__no_attachments_or_zero_size__when__upload__then__storage_and_content_are_not_opened() {
        val empty = PostUpload(
            "empty.txt",
            "text/plain",
            0,
            PostUpload.Content { throw AssertionError("Empty attachments must not be opened") },
        )

        assertTrue(uploader.upload(null).isEmpty())
        assertTrue(uploader.upload(listOf()).isEmpty())
        assertTrue(uploader.upload(listOf(empty)).isEmpty())
        verifyNoInteractions(s3)
    }

    @ParameterizedTest
    @MethodSource("allowedMedia")
    fun given__allowed_media__when__upload__then__bytes_mime_folder_and_public_url_match_legacy(
        mime: String,
        folder: String,
    ) {
        val bytes = byteArrayOf(0, 7, 255.toByte(), 11)
        val stream = TrackingStream(bytes)
        val requests = ArrayList<PutObjectRequest>()
        whenever(s3.putObject(any(PutObjectRequest::class.java), any(RequestBody::class.java))).thenAnswer { invocation ->
            val request = invocation.getArgument<PutObjectRequest>(0)
            val body = invocation.getArgument<RequestBody>(1)
            requests.add(request)
            assertEquals(bytes.size.toLong(), body.optionalContentLength().orElseThrow().toLong())
            assertArrayEquals(bytes, body.contentStreamProvider().newStream().readAllBytes())
            assertFalse(stream.closed)
            null
        }

        val urls = uploader.upload(
            listOf(PostUpload("pet.Photo.PNG", mime, bytes.size.toLong(), PostUpload.Content { stream })),
        )

        val request = requests.first()
        assertEquals(BUCKET, request.bucket())
        assertEquals(mime, request.contentType())
        assertTrue(request.key().matches(Regex(folder + "[0-9a-f-]{36}\\.PNG")))
        assertEquals(listOf(PUBLIC_BASE + "/" + request.key()), urls)
        assertTrue(stream.closed)
    }

    @ParameterizedTest
    @ValueSource(strings = ["f3-owner", "f3-owner/", "f3-owner/nested", "f3-owner/nested/"])
    fun given__configured_prefix__when__upload__then__normalized_prefix_and_attachment_order_are_preserved(
        prefix: String,
    ) {
        val requests = ArrayList<PutObjectRequest>()
        whenever(s3.putObject(any(PutObjectRequest::class.java), any(RequestBody::class.java))).thenAnswer { invocation ->
            requests.add(invocation.getArgument(0))
            null
        }
        val prefixed = S3PostMediaUploader(s3, BUCKET, PUBLIC_BASE, prefix)

        val urls = prefixed.upload(
            listOf(
                upload("first.png", "image/png", byteArrayOf(1)),
                upload("empty", null, byteArrayOf()),
                upload("second.mp4", "video/mp4", byteArrayOf(2)),
            ),
        )

        val normalized = if (prefix.endsWith("/")) prefix else "$prefix/"
        assertEquals(2, requests.size)
        assertTrue(requests[0].key().startsWith(normalized + "posts/images/"))
        assertTrue(requests[1].key().startsWith(normalized + "posts/videos/"))
        assertEquals(requests.map { request -> PUBLIC_BASE + "/" + request.key() }, urls)
    }

    @ParameterizedTest
    @MethodSource("filenames")
    fun given__filename__when__upload__then__only_last_extension_is_preserved(filename: String?, extension: String) {
        val requests = ArrayList<PutObjectRequest>()
        whenever(s3.putObject(any(PutObjectRequest::class.java), any(RequestBody::class.java))).thenAnswer { invocation ->
            requests.add(invocation.getArgument(0))
            null
        }

        uploader.upload(listOf(upload(filename, "image/png", byteArrayOf(1))))

        assertTrue(requests.first().key().matches(Regex("posts/images/[0-9a-f-]{36}$extension")))
    }

    @ParameterizedTest
    @MethodSource("unsupportedMedia")
    fun given__unsupported_media__when__upload__then__legacy_failure_is_wrapped_before_opening_content(
        contentType: String?,
        expectedMessage: String,
    ) {
        val upload = PostUpload(
            "pet.txt",
            contentType,
            1,
            PostUpload.Content { throw AssertionError("Rejected media must not be opened") },
        )

        val failure = assertThrows(RuntimeException::class.java) { uploader.upload(listOf(upload)) }

        assertEquals("파일 업로드 중 오류가 발생했습니다", failure.message)
        assertInstanceOf(IllegalArgumentException::class.java, failure.cause)
        assertEquals(expectedMessage, failure.cause!!.message)
        verifyNoInteractions(s3)
    }

    @Test
    fun given__opening_content_fails__when__upload__then__failure_is_wrapped_without_storage_call() {
        val cause = IOException("synthetic read failure")
        val upload = PostUpload("pet.png", "image/png", 1, PostUpload.Content { throw cause })

        val failure = assertThrows(RuntimeException::class.java) { uploader.upload(listOf(upload)) }

        assertEquals("파일 업로드 중 오류가 발생했습니다", failure.message)
        assertSame(cause, failure.cause)
        verifyNoInteractions(s3)
    }

    @Test
    fun given__second_put_fails__when__upload__then__streams_close_and_no_cleanup_or_later_put_occurs() {
        val first = TrackingStream(byteArrayOf(1))
        val second = TrackingStream(byteArrayOf(2))
        val cause = IllegalStateException("synthetic storage failure")
        whenever(s3.putObject(any(PutObjectRequest::class.java), any(RequestBody::class.java)))
            .thenReturn(null).thenThrow(cause)
        val third = PostUpload(
            "third.png",
            "image/png",
            1,
            PostUpload.Content { throw AssertionError("No later attachment may open after a failure") },
        )

        val failure = assertThrows(RuntimeException::class.java) {
            uploader.upload(
                listOf(
                    PostUpload("first.png", "image/png", 1, PostUpload.Content { first }),
                    PostUpload("second.png", "image/png", 1, PostUpload.Content { second }),
                    third,
                ),
            )
        }

        assertEquals("파일 업로드 중 오류가 발생했습니다", failure.message)
        assertSame(cause, failure.cause)
        assertTrue(first.closed)
        assertTrue(second.closed)
        verify(s3, times(2))
            .putObject(any(PutObjectRequest::class.java), any(RequestBody::class.java))
        verifyNoMoreInteractions(s3)
    }

    @ParameterizedTest
    @ValueSource(ints = [8, 200_000])
    fun given__non_resettable_source__when__sdk_reads_for_signing_and_sending__then__fresh_bytes_and_closed_streams(
        length: Int,
    ) {
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
        whenever(s3.putObject(any(PutObjectRequest::class.java), any(RequestBody::class.java))).thenAnswer { invocation ->
            val body = invocation.getArgument<RequestBody>(1)
            val signing: InputStream = body.contentStreamProvider().newStream()
            assertFalse(signing.markSupported())
            assertArrayEquals(bytes, signing.readAllBytes())
            val sending: InputStream = body.contentStreamProvider().newStream()
            assertArrayEquals(bytes, sending.readAllBytes())
            null
        }

        uploader.upload(listOf(upload))

        assertEquals(2, opened.size)
        assertTrue(opened.all { stream -> stream.closed })
        verify(s3).putObject(any(PutObjectRequest::class.java), any(RequestBody::class.java))
    }

    @Test
    fun given__source_reopen_failure__when__sdk_requests_another_read__then__previous_stream_closes_and_upload_fails() {
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
        whenever(s3.putObject(any(PutObjectRequest::class.java), any(RequestBody::class.java))).thenAnswer { invocation ->
            val body = invocation.getArgument<RequestBody>(1)
            body.contentStreamProvider().newStream().readAllBytes()
            body.contentStreamProvider().newStream()
            null
        }

        val failure = assertThrows(RuntimeException::class.java) { uploader.upload(listOf(upload)) }

        assertSame(cause, assertInstanceOf(UncheckedIOException::class.java, failure.cause).cause)
        assertTrue(first.closed)
        verify(s3).putObject(any(PutObjectRequest::class.java), any(RequestBody::class.java))
        verifyNoMoreInteractions(s3)
    }

    @ParameterizedTest
    @ValueSource(strings = ["/f3/", "../", "f3/../", "f3//", " f3/", "f3/ ", "f3\\", "f3?x=1", "f3%2f"])
    fun given__invalid_prefix__when__construct__then__legacy_configuration_validation_rejects_it(prefix: String) {
        assertThrows(IllegalArgumentException::class.java) {
            S3PostMediaUploader(s3, BUCKET, PUBLIC_BASE, prefix)
        }
        verifyNoInteractions(s3)
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
