package com.pawbridge.backend.community.internal.report.infrastructure

import com.pawbridge.backend.community.internal.post.application.PostMediaRemover
import com.pawbridge.backend.community.internal.post.application.PostUpload
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when` as whenever
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import java.io.ByteArrayInputStream

class S3AnimalReportMediaUploaderTest {
    @Test
    fun given__second_upload_failure__when__uploading_reports__then__delete_only_prior_report_urls_and_keep_report_folder() {
        val s3 = mock(S3Client::class.java)
        val remover = mock(PostMediaRemover::class.java)
        val keys = ArrayList<String>()
        whenever(s3.putObject(any(PutObjectRequest::class.java), any(RequestBody::class.java))).thenAnswer { call ->
            val request = call.getArgument<PutObjectRequest>(0)
            keys.add(request.key())
            if (keys.size == 2) {
                throw IllegalStateException("private-storage")
            }
            val body = call.getArgument<RequestBody>(1)
            assertThat(body.contentStreamProvider().newStream().readAllBytes()).containsExactly(1, 2, 3)
            assertThat(body.contentStreamProvider().newStream().readAllBytes()).containsExactly(1, 2, 3)
            PutObjectResponse.builder().build()
        }
        val uploads = S3AnimalReportMediaUploader(s3, "fixture-bucket", "https://fixture.invalid/", "owned/", remover)
        val photo = PostUpload("one.png", "image/png", 3, PostUpload.Content { ByteArrayInputStream(byteArrayOf(1, 2, 3)) })

        assertThatThrownBy { uploads.upload(listOf(photo, photo)) }
            .isInstanceOf(RuntimeException::class.java)
            .hasMessage("파일 업로드 중 오류가 발생했습니다")
        assertThat(keys).hasSize(2).allSatisfy({ key ->
            assertThat(key).startsWith("owned/reports/images/").endsWith(".png")
        })
        verify(remover).remove(listOf("https://fixture.invalid/" + keys.first()))
    }

    @Test
    fun given__no_photos__when__uploading__then__no_external_io() {
        val s3 = mock(S3Client::class.java)
        val remover = mock(PostMediaRemover::class.java)
        val uploads = S3AnimalReportMediaUploader(s3, "fixture", "https://fixture.invalid", "", remover)

        assertThat(uploads.upload(null)).isEmpty()
        assertThat(uploads.upload(listOf())).isEmpty()
        verifyNoInteractions(s3, remover)
    }

    @Test
    fun given__empty_prefix__when__uploading_report_photo__then__key_is_at_bucket_root_and_url_matches_legacy_format() {
        val s3 = mock(S3Client::class.java)
        val remover = mock(PostMediaRemover::class.java)
        val keys = ArrayList<String>()
        whenever(s3.putObject(any(PutObjectRequest::class.java), any(RequestBody::class.java))).thenAnswer { call ->
            val request = call.getArgument<PutObjectRequest>(0)
            keys.add(request.key())
            PutObjectResponse.builder().build()
        }
        val uploads = S3AnimalReportMediaUploader(s3, "fixture-bucket", "https://fixture.invalid///", "", remover)
        val photo = PostUpload("one.png", "image/png", 3, PostUpload.Content { ByteArrayInputStream(byteArrayOf(1, 2, 3)) })

        val urls = uploads.upload(listOf(photo))

        assertThat(keys).singleElement().asString().matches("reports/images/[0-9a-f-]{36}\\.png")
        assertThat(urls).containsExactly("https://fixture.invalid/" + keys.first())
        verifyNoInteractions(remover)
    }
}
