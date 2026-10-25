package com.pawbridge.backend.community.internal.post.infrastructure.media

import com.pawbridge.backend.community.internal.post.application.PostUpload
import com.pawbridge.backend.community.internal.report.infrastructure.S3AnimalReportMediaUploader
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifySequence
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import java.io.ByteArrayInputStream
import java.util.stream.Stream

class S3PostMediaRemoverTest {
    private val s3 = mockk<S3Client>()
    private val base = "https://fixture.invalid"
    private val remover = S3PostMediaRemover(s3, "synthetic-bucket", "$base///", "owner")

    @Test
    fun `목록이 null이거나 비었을 때__삭제하면__SDK를 호출하지 않는다`() {
        // given
        val nothing: List<String?>? = null

        // when
        remover.remove(nothing)
        remover.remove(listOf())

        // then
        confirmVerified(s3)
    }

    @Test
    fun `중복되고 null이 섞인 순서 있는 URL일 때__삭제하면__유효한 호출만 순서와 중복을 보존해 보낸다`() {
        // given
        every { s3.deleteObject(any<DeleteObjectRequest>()) } returns DeleteObjectResponse.builder().build()

        // when
        remover.remove(
            listOf(
                "$base/owner/posts/images/first.png",
                null,
                "$base/owner/posts/images/first.png",
                "$base/owner/posts/videos/last.mp4",
            ),
        )

        // then
        val requests = mutableListOf<DeleteObjectRequest>()
        verify(exactly = 3) { s3.deleteObject(capture(requests)) }
        assertEquals(
            listOf("owner/posts/images/first.png", "owner/posts/images/first.png", "owner/posts/videos/last.mp4"),
            requests.map { it.key() },
        )
        assertTrue(requests.all { request -> "synthetic-bucket" == request.bucket() })
        confirmVerified(s3)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://elsewhere.invalid/owner/a",
            "https://fixture.invalid.evil/owner/a",
            "http://fixture.invalid/owner/a",
            "https://fixture.invalid/other/a",
            "https://fixture.invalid/owner/",
            "https://fixture.invalid/owner/a%2fb",
            "https://fixture.invalid/owner/a%252fb",
            "https://fixture.invalid/owner/../a",
            "https://fixture.invalid/owner/./a",
            "https://fixture.invalid/owner//a",
            "https://fixture.invalid/owner/a?token=secret",
            "https://fixture.invalid/owner/a#fragment",
            "https://fixture.invalid/owner/a\\b",
            "",
        ],
    )
    fun `유효한 URL 앞에 잘못된 URL이 있을 때__삭제하면__그 URL만 거부하고 계속한다`(invalid: String) {
        // given
        every { s3.deleteObject(any<DeleteObjectRequest>()) } returns DeleteObjectResponse.builder().build()

        // when
        remover.remove(listOf(invalid, "$base/owner/posts/images/next.png"))

        // then
        verify(exactly = 1) {
            s3.deleteObject(
                DeleteObjectRequest.builder().bucket("synthetic-bucket").key("owner/posts/images/next.png").build(),
            )
        }
        confirmVerified(s3)
    }

    @Test
    fun `첫 URL에서 SDK가 실패할 때__삭제하면__재시도 없이 다음 URL을 시도한다`() {
        // given
        every { s3.deleteObject(any<DeleteObjectRequest>()) } throws
            IllegalStateException("secret signed URL must not escape") andThen
            DeleteObjectResponse.builder().build()

        // when
        val removal = Executable { remover.remove(listOf("$base/owner/first", "$base/owner/second")) }

        // then
        assertDoesNotThrow(removal)
        verifySequence {
            s3.deleteObject(DeleteObjectRequest.builder().bucket("synthetic-bucket").key("owner/first").build())
            s3.deleteObject(DeleteObjectRequest.builder().bucket("synthetic-bucket").key("owner/second").build())
        }
    }

    @Test
    fun `설정된 접두사가 비었을 때__삭제하면__소유한 기준 URL은 허용하고 경로 탈출과 인코딩은 막는다`() {
        // given
        val root = S3PostMediaRemover(s3, "synthetic-bucket", base, "")

        every { s3.deleteObject(any<DeleteObjectRequest>()) } returns DeleteObjectResponse.builder().build()

        // when
        root.remove(
            listOf("$base/posts/images/a.png", "$base/../outside", "$base/a%2fb", "$base/a?token=x"),
        )

        // then
        verify(exactly = 1) {
            s3.deleteObject(
                DeleteObjectRequest.builder().bucket("synthetic-bucket").key("posts/images/a.png").build(),
            )
        }
        confirmVerified(s3)
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["/owner/", "../", "owner/../", "owner//", " owner/", "owner/ ", "owner\\", "owner?x=1", "owner%2f"],
    )
    fun `잘못된 접두사가 설정됐을 때__생성하면__SDK 호출 없이 거부한다`(prefix: String) {
        // given
        val invalidPrefix = prefix

        // when
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            S3PostMediaRemover(s3, "synthetic-bucket", base, invalidPrefix)
        }

        // then
        assertEquals(IllegalArgumentException::class.java, thrown.javaClass)
        confirmVerified(s3)
    }

    @ParameterizedTest
    @MethodSource("opaqueUploadSuffixes")
    fun `업로더가 만든 불투명 키일 때__저장된 URL을 삭제하면__다시 해석하지 않고 올린 키 그대로 삭제한다`(
        suffix: String,
        mime: String,
    ) {
        // given
        val uploader = S3PostMediaUploader(s3, "synthetic-bucket", base, "owner")
        val file = PostUpload("pet$suffix", mime, 1, PostUpload.Content { ByteArrayInputStream(byteArrayOf(1)) })

        every { s3.putObject(any<PutObjectRequest>(), any<RequestBody>()) } returns PutObjectResponse.builder().build()
        every { s3.deleteObject(any<DeleteObjectRequest>()) } returns DeleteObjectResponse.builder().build()

        // when
        val savedUrls = uploader.upload(listOf(file))
        remover.remove(savedUrls)

        // then
        val uploaded = slot<PutObjectRequest>()
        verify(exactly = 1) { s3.putObject(capture(uploaded), any<RequestBody>()) }
        val put = uploaded.captured
        val folder = "owner/posts/" + (if (mime.startsWith("video/")) "videos/" else "images/")
        assertTrue(put.key().startsWith(folder))
        assertEquals(suffix, put.key().substring(folder.length + 36))
        assertEquals(listOf(base + "/" + put.key()), savedUrls)
        verify(exactly = 1) {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(put.bucket()).key(put.key()).build())
        }
        confirmVerified(s3)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://elsewhere.invalid/owner/posts/images/12345678-1234-4234-8234-123456789abc.png?x",
            "https://fixture.invalid/other/posts/images/12345678-1234-4234-8234-123456789abc.png?x",
            "https://fixture.invalid/owner%2fposts/images/12345678-1234-4234-8234-123456789abc.png?x",
            "https://fixture.invalid/owner/posts%2fimages/12345678-1234-4234-8234-123456789abc.png?x",
            "https://fixture.invalid/owner/posts/images/not-a-uuid.png?x",
            "https://fixture.invalid/owner/posts/images/12345678-1234-4234-8234-123456789ABC.png?x",
            "https://fixture.invalid/owner/posts/images/12345678-1234-1234-8234-123456789abc.png?x",
            "https://fixture.invalid/owner/posts/reports/12345678-1234-4234-8234-123456789abc.png?x",
            "https://fixture.invalid/owner/posts/images/12345678-1234-4234-8234-123456789abc.part.png?x",
            "https://fixture.invalid/owner/posts/images/12345678-1234-4234-8234-123456789abc.png/../other",
            "https://fixture.invalid/owner/posts/images/\n12345678-1234-4234-8234-123456789abc.png",
        ],
    )
    fun `업로더 문법 밖의 문자가 있는 불투명 URL일 때__삭제하면__범위와 경로 탈출 방어를 유지한다`(
        url: String,
    ) {
        // given
        val unsafe = listOf(url)

        // when
        remover.remove(unsafe)

        // then
        confirmVerified(s3)
    }

    @ParameterizedTest
    @ValueSource(strings = ["image/png", "video/mp4"])
    fun `접두사가 빈 게시글 업로드일 때__저장된 URL을 삭제하면__루트 키 그대로 삭제한다`(mime: String) {
        // given
        val rootUploader = S3PostMediaUploader(s3, "synthetic-bucket", base, "")
        val rootRemover = S3PostMediaRemover(s3, "synthetic-bucket", base, "")
        val file = PostUpload("pet.png", mime, 1, PostUpload.Content { ByteArrayInputStream(byteArrayOf(1)) })

        every { s3.putObject(any<PutObjectRequest>(), any<RequestBody>()) } returns PutObjectResponse.builder().build()
        every { s3.deleteObject(any<DeleteObjectRequest>()) } returns DeleteObjectResponse.builder().build()

        // when
        val savedUrls = rootUploader.upload(listOf(file))
        rootRemover.remove(savedUrls)

        // then
        val uploaded = slot<PutObjectRequest>()
        verify(exactly = 1) { s3.putObject(capture(uploaded), any<RequestBody>()) }
        val key = uploaded.captured.key()
        val folder = if (mime.startsWith("video/")) "posts/videos/" else "posts/images/"
        assertTrue(key.startsWith(folder))
        assertEquals(listOf(base + "/" + key), savedUrls)
        verify(exactly = 1) {
            s3.deleteObject(DeleteObjectRequest.builder().bucket("synthetic-bucket").key(key).build())
        }
    }

    @Test
    fun `접두사가 빈 제보 업로드일 때__저장된 URL을 삭제하면__루트 키 그대로 삭제한다`() {
        // given
        val rootRemover = S3PostMediaRemover(s3, "synthetic-bucket", base, "")
        val reportUploader = S3AnimalReportMediaUploader(s3, "synthetic-bucket", base, "", rootRemover)
        val file = PostUpload("lost.png", "image/png", 1, PostUpload.Content { ByteArrayInputStream(byteArrayOf(1)) })

        every { s3.putObject(any<PutObjectRequest>(), any<RequestBody>()) } returns PutObjectResponse.builder().build()
        every { s3.deleteObject(any<DeleteObjectRequest>()) } returns DeleteObjectResponse.builder().build()

        // when
        val savedUrls = reportUploader.upload(listOf(file))
        rootRemover.remove(savedUrls)

        // then
        val uploaded = slot<PutObjectRequest>()
        verify(exactly = 1) { s3.putObject(capture(uploaded), any<RequestBody>()) }
        val key = uploaded.captured.key()
        assertTrue(key.startsWith("reports/images/"))
        assertEquals(listOf(base + "/" + key), savedUrls)
        verify(exactly = 1) {
            s3.deleteObject(DeleteObjectRequest.builder().bucket("synthetic-bucket").key(key).build())
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://elsewhere.invalid/posts/images/a.png",
            "https://fixture.invalid.evil/posts/images/a.png",
            "http://fixture.invalid/posts/images/a.png",
            "https://fixture.invalid",
            "https://fixture.invalid/",
            "https://fixture.invalid//posts/images/a.png",
            "https://fixture.invalid/posts//images/a.png",
            "https://fixture.invalid/posts/../images/a.png",
            "https://fixture.invalid/posts/images/a%2fb.png",
            "https://fixture.invalid/posts/images/a.png?token=secret",
            "https://fixture.invalid/posts/images/a.png#fragment",
            "https://fixture.invalid/posts\\images/a.png",
            "",
        ],
    )
    fun `접두사가 빈 상태에서 공개 기준 밖이거나 안전하지 않은 키의 URL일 때__삭제하면__SDK를 호출하지 않는다`(
        url: String,
    ) {
        // given
        val rootRemover = S3PostMediaRemover(s3, "synthetic-bucket", base, "")

        // when
        rootRemover.remove(listOf(url))

        // then
        confirmVerified(s3)
    }

    companion object {
        @JvmStatic
        fun opaqueUploadSuffixes(): Stream<Arguments> =
            listOf(
                "", ".", ".pn%67", ".png%2Fopaque", ".png?token=synthetic", ".png#fragment",
                ".pn\\g", ".png/segment", ".png//segment", ".png/%2e%2e/object",
                ".png?target=//elsewhere/object", ".png\nline", ".png\rline", ".png\tline", ".png\u0000tail",
                ".png\u007Ftail",
            ).flatMap { suffix ->
                listOf(Arguments.of(suffix, "image/png"), Arguments.of(suffix, "video/mp4"))
            }.stream()
    }
}
