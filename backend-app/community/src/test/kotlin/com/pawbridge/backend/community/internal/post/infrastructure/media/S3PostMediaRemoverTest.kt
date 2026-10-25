package com.pawbridge.backend.community.internal.post.infrastructure.media

import com.pawbridge.backend.community.internal.post.application.PostUpload
import com.pawbridge.backend.community.internal.report.infrastructure.S3AnimalReportMediaUploader
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
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when` as whenever
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.io.ByteArrayInputStream
import java.util.stream.Stream

class S3PostMediaRemoverTest {
    private val s3 = mock(S3Client::class.java)
    private val base = "https://fixture.invalid"
    private val remover = S3PostMediaRemover(s3, "synthetic-bucket", "$base///", "owner")

    @Test
    fun given__null_or_empty_list__when__remove__then__sdk_is_not_called() {
        remover.remove(null)
        remover.remove(listOf())
        verifyNoInteractions(s3)
    }

    @Test
    fun given__duplicate_null_and_ordered_urls__when__remove__then__valid_calls_preserve_order_and_duplicates() {
        val requests = ArrayList<DeleteObjectRequest>()
        whenever(s3.deleteObject(any(DeleteObjectRequest::class.java))).thenAnswer { call ->
            requests.add(call.getArgument(0))
            null
        }

        remover.remove(
            listOf(
                "$base/owner/posts/images/first.png",
                null,
                "$base/owner/posts/images/first.png",
                "$base/owner/posts/videos/last.mp4",
            ),
        )

        assertEquals(
            listOf("owner/posts/images/first.png", "owner/posts/images/first.png", "owner/posts/videos/last.mp4"),
            requests.map { it.key() },
        )
        assertTrue(requests.all { request -> "synthetic-bucket" == request.bucket() })
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
    fun given__invalid_url_before_valid_url__when__remove__then__reject_only_that_url_and_continue(invalid: String) {
        remover.remove(listOf(invalid, "$base/owner/posts/images/next.png"))

        verify(s3).deleteObject(
            DeleteObjectRequest.builder().bucket("synthetic-bucket").key("owner/posts/images/next.png").build(),
        )
        verifyNoMoreInteractions(s3)
    }

    @Test
    fun given__sdk_failure_on_first_url__when__remove__then__next_url_is_attempted_without_application_retry() {
        whenever(s3.deleteObject(any(DeleteObjectRequest::class.java)))
            .thenThrow(IllegalStateException("secret signed URL must not escape"))
            .thenReturn(null)

        assertDoesNotThrow(Executable { remover.remove(listOf("$base/owner/first", "$base/owner/second")) })

        val order = inOrder(s3)
        order.verify(s3).deleteObject(
            DeleteObjectRequest.builder().bucket("synthetic-bucket").key("owner/first").build(),
        )
        order.verify(s3).deleteObject(
            DeleteObjectRequest.builder().bucket("synthetic-bucket").key("owner/second").build(),
        )
        order.verifyNoMoreInteractions()
    }

    @Test
    fun given__empty_configured_prefix__when__remove__then__owned_base_is_allowed_but_traversal_and_encoding_are_not() {
        val root = S3PostMediaRemover(s3, "synthetic-bucket", base, "")

        root.remove(
            listOf("$base/posts/images/a.png", "$base/../outside", "$base/a%2fb", "$base/a?token=x"),
        )

        verify(s3).deleteObject(
            DeleteObjectRequest.builder().bucket("synthetic-bucket").key("posts/images/a.png").build(),
        )
        verifyNoMoreInteractions(s3)
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["/owner/", "../", "owner/../", "owner//", " owner/", "owner/ ", "owner\\", "owner?x=1", "owner%2f"],
    )
    fun given__invalid_configured_prefix__when__construct__then__reject_without_sdk_calls(prefix: String) {
        assertThrows(IllegalArgumentException::class.java) {
            S3PostMediaRemover(s3, "synthetic-bucket", base, prefix)
        }
        verifyNoInteractions(s3)
    }

    @ParameterizedTest
    @MethodSource("opaqueUploadSuffixes")
    fun given__uploader_generated_opaque_key__when__removing_saved_url__then__delete_exact_put_key_without_reinterpretation(
        suffix: String,
        mime: String,
    ) {
        val uploaded = ArrayList<PutObjectRequest>()
        whenever(s3.putObject(any(PutObjectRequest::class.java), any(RequestBody::class.java))).thenAnswer { call ->
            uploaded.add(call.getArgument(0))
            null
        }
        val uploader = S3PostMediaUploader(s3, "synthetic-bucket", base, "owner")
        val file = PostUpload("pet$suffix", mime, 1, PostUpload.Content { ByteArrayInputStream(byteArrayOf(1)) })
        val savedUrls = uploader.upload(listOf(file))

        remover.remove(savedUrls)

        val put = uploaded.first()
        val folder = "owner/posts/" + (if (mime.startsWith("video/")) "videos/" else "images/")
        assertTrue(put.key().startsWith(folder))
        assertEquals(suffix, put.key().substring(folder.length + 36))
        assertEquals(listOf(base + "/" + put.key()), savedUrls)
        verify(s3).putObject(any(PutObjectRequest::class.java), any(RequestBody::class.java))
        verify(s3).deleteObject(DeleteObjectRequest.builder().bucket(put.bucket()).key(put.key()).build())
        verifyNoMoreInteractions(s3)
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
    fun given__opaque_characters_outside_exact_uploader_grammar__when__remove__then__retain_scope_and_traversal_guards(
        url: String,
    ) {
        remover.remove(listOf(url))
        verifyNoInteractions(s3)
    }

    @ParameterizedTest
    @ValueSource(strings = ["image/png", "video/mp4"])
    fun given__empty_prefix_post_upload__when__removing_saved_url__then__delete_the_exact_root_key(mime: String) {
        val uploaded = ArrayList<PutObjectRequest>()
        whenever(s3.putObject(any(PutObjectRequest::class.java), any(RequestBody::class.java))).thenAnswer { call ->
            uploaded.add(call.getArgument(0))
            null
        }
        val rootUploader = S3PostMediaUploader(s3, "synthetic-bucket", base, "")
        val rootRemover = S3PostMediaRemover(s3, "synthetic-bucket", base, "")
        val file = PostUpload("pet.png", mime, 1, PostUpload.Content { ByteArrayInputStream(byteArrayOf(1)) })
        val savedUrls = rootUploader.upload(listOf(file))

        rootRemover.remove(savedUrls)

        val key = uploaded.first().key()
        val folder = if (mime.startsWith("video/")) "posts/videos/" else "posts/images/"
        assertTrue(key.startsWith(folder))
        assertEquals(listOf(base + "/" + key), savedUrls)
        verify(s3).deleteObject(DeleteObjectRequest.builder().bucket("synthetic-bucket").key(key).build())
    }

    @Test
    fun given__empty_prefix_report_upload__when__removing_saved_url__then__delete_the_exact_root_key() {
        val uploaded = ArrayList<PutObjectRequest>()
        whenever(s3.putObject(any(PutObjectRequest::class.java), any(RequestBody::class.java))).thenAnswer { call ->
            uploaded.add(call.getArgument(0))
            null
        }
        val rootRemover = S3PostMediaRemover(s3, "synthetic-bucket", base, "")
        val reportUploader = S3AnimalReportMediaUploader(s3, "synthetic-bucket", base, "", rootRemover)
        val file = PostUpload("lost.png", "image/png", 1, PostUpload.Content { ByteArrayInputStream(byteArrayOf(1)) })
        val savedUrls = reportUploader.upload(listOf(file))

        rootRemover.remove(savedUrls)

        val key = uploaded.first().key()
        assertTrue(key.startsWith("reports/images/"))
        assertEquals(listOf(base + "/" + key), savedUrls)
        verify(s3).deleteObject(DeleteObjectRequest.builder().bucket("synthetic-bucket").key(key).build())
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
    fun given__empty_prefix__when__removing_url_outside_public_base_or_with_unsafe_key__then__do_not_call_the_sdk(
        url: String,
    ) {
        val rootRemover = S3PostMediaRemover(s3, "synthetic-bucket", base, "")

        rootRemover.remove(listOf(url))

        verifyNoInteractions(s3)
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
