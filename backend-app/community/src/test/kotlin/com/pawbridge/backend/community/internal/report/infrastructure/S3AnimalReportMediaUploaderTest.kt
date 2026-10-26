package com.pawbridge.backend.community.internal.report.infrastructure

import com.pawbridge.backend.community.internal.post.application.PostMediaRemover
import com.pawbridge.backend.community.internal.post.application.PostUpload
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import java.io.ByteArrayInputStream

class S3AnimalReportMediaUploaderTest {
    @Test
    fun `두 번째 업로드가 실패할 때__신고 사진을 올리면__앞서 올린 신고 URL만 지우고 신고 폴더는 남긴다`() {
        // given
        val s3 = mockk<S3Client>()
        val remover = mockk<PostMediaRemover>()
        val keys = ArrayList<String>()
        val uploads = S3AnimalReportMediaUploader(s3, "fixture-bucket", "https://fixture.invalid/", "owned/", remover)
        val photo = PostUpload("one.png", "image/png", 3, PostUpload.Content { ByteArrayInputStream(byteArrayOf(1, 2, 3)) })

        every { s3.putObject(any<PutObjectRequest>(), any<RequestBody>()) } answers {
            val request = firstArg<PutObjectRequest>()
            keys.add(request.key())
            if (keys.size == 2) {
                throw IllegalStateException("private-storage")
            }
            val body = secondArg<RequestBody>()
            assertThat(body.contentStreamProvider().newStream().readAllBytes()).containsExactly(1, 2, 3)
            assertThat(body.contentStreamProvider().newStream().readAllBytes()).containsExactly(1, 2, 3)
            PutObjectResponse.builder().build()
        }
        justRun { remover.remove(any()) }

        // when
        val thrown = assertThatThrownBy { uploads.upload(listOf(photo, photo)) }

        // then
        thrown
            .isInstanceOf(RuntimeException::class.java)
            .hasMessage("파일 업로드 중 오류가 발생했습니다")
        assertThat(keys).hasSize(2).allSatisfy({ key ->
            assertThat(key).startsWith("owned/reports/images/").endsWith(".png")
        })
        verify(exactly = 1) { remover.remove(listOf("https://fixture.invalid/" + keys.first())) }
        confirmVerified(remover)
    }

    @Test
    fun `올릴 사진이 없을 때__업로드하면__외부 입출력을 하지 않는다`() {
        // given
        val s3 = mockk<S3Client>()
        val remover = mockk<PostMediaRemover>()
        val uploads = S3AnimalReportMediaUploader(s3, "fixture", "https://fixture.invalid", "", remover)

        // when
        val fromNull = uploads.upload(null)
        val fromEmpty = uploads.upload(listOf())

        // then
        assertThat(fromNull).isEmpty()
        assertThat(fromEmpty).isEmpty()
        confirmVerified(s3, remover)
    }

    @Test
    fun `접두사가 비어 있을 때__신고 사진을 올리면__키는 버킷 루트에 놓이고 URL은 기존 형식과 같다`() {
        // given
        val s3 = mockk<S3Client>()
        val remover = mockk<PostMediaRemover>()
        val keys = ArrayList<String>()
        val uploads = S3AnimalReportMediaUploader(s3, "fixture-bucket", "https://fixture.invalid///", "", remover)
        val photo = PostUpload("one.png", "image/png", 3, PostUpload.Content { ByteArrayInputStream(byteArrayOf(1, 2, 3)) })

        every { s3.putObject(any<PutObjectRequest>(), any<RequestBody>()) } answers {
            val request = firstArg<PutObjectRequest>()
            keys.add(request.key())
            PutObjectResponse.builder().build()
        }

        // when
        val urls = uploads.upload(listOf(photo))

        // then
        assertThat(keys).singleElement().asString().matches("reports/images/[0-9a-f-]{36}\\.png")
        assertThat(urls).containsExactly("https://fixture.invalid/" + keys.first())
        confirmVerified(remover)
    }
}
