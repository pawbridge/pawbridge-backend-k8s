package com.pawbridge.backend.community.internal.report.infrastructure

import com.pawbridge.backend.community.internal.post.application.PostMediaRemover
import com.pawbridge.backend.community.internal.post.application.PostMediaUploader
import com.pawbridge.backend.community.internal.post.application.PostUpload
import java.io.IOException
import java.io.InputStream
import java.io.UncheckedIOException
import java.util.Locale
import java.util.UUID
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.ContentStreamProvider
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest

/** Report-only folder and legacy compensation for earlier files on a partial upload failure. */
class S3AnimalReportMediaUploader(
    private val s3: S3Client,
    private val bucket: String,
    publicBaseUrl: String,
    objectPrefix: String,
    private val remover: PostMediaRemover,
) : PostMediaUploader {

    private val publicBaseUrl: String = removeTrailingSlashes(publicBaseUrl)
    private val objectPrefix: String = normalizeObjectPrefix(objectPrefix)

    override fun upload(uploads: List<PostUpload>?): List<String> {
        val urls = ArrayList<String>()
        if (uploads == null || uploads.isEmpty()) {
            return urls
        }
        for (upload in uploads) {
            if (upload.size == 0L) {
                continue
            }
            try {
                urls.add(uploadSingleFile(upload))
            } catch (failure: IOException) {
                throw uploadFailure(urls, failure)
            } catch (failure: RuntimeException) {
                throw uploadFailure(urls, failure)
            }
        }
        return urls
    }

    private fun uploadFailure(urls: List<String>, failure: Exception): RuntimeException {
        remover.remove(urls)
        return RuntimeException("파일 업로드 중 오류가 발생했습니다", failure)
    }

    private fun uploadSingleFile(upload: PostUpload): String {
        validateMedia(upload)
        val originalFilename = upload.originalFilename
        val extension = if (originalFilename != null && originalFilename.contains(".")) {
            originalFilename.substring(originalFilename.lastIndexOf('.'))
        } else {
            ""
        }
        val folder = "reports/images/"
        val key = objectPrefix + folder + UUID.randomUUID() + extension
        val request = PutObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .contentType(upload.contentType)
            .build()
        ReopeningContent(upload.content).use { content ->
            s3.putObject(request, RequestBody.fromContentProvider(content, upload.size, upload.contentType))
        }
        return "$publicBaseUrl/$key"
    }

    /** SDK signing and sending may request separate streams; multipart disk streams cannot reset. */
    private class ReopeningContent(private val source: PostUpload.Content) : ContentStreamProvider, AutoCloseable {
        private var current: InputStream? = source.open()
        private var first = true

        override fun newStream(): InputStream? {
            if (first) {
                first = false
                return current
            }
            try {
                close()
                current = source.open()
                return current
            } catch (failure: IOException) {
                throw UncheckedIOException(failure)
            }
        }

        override fun close() {
            val closing = current
            current = null
            closing?.close()
        }
    }

    private companion object {
        private val IMAGE_TYPES = listOf("image/jpeg", "image/jpg", "image/png", "image/gif", "image/webp")
        private val VIDEO_TYPES = listOf("video/mp4", "video/quicktime", "video/x-msvideo", "video/x-matroska")
        private val OBJECT_PREFIX = Regex("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*/?")

        private fun validateMedia(upload: PostUpload) {
            val contentType = upload.contentType
            if (contentType == null) {
                throw IllegalArgumentException(
                    String.format("파일 타입을 확인할 수 없습니다. (파일: %s)", upload.originalFilename),
                )
            }
            // Java String.toLowerCase() uses the default locale.
            val lowerContentType = contentType.lowercase(Locale.getDefault())
            if (!IMAGE_TYPES.contains(lowerContentType) && !VIDEO_TYPES.contains(lowerContentType)) {
                throw IllegalArgumentException(
                    String.format(
                        "지원하지 않는 파일 형식입니다. (업로드한 파일: %s, 타입: %s). " +
                            "허용된 형식 - 이미지: jpg, jpeg, png, gif, webp / 영상: mp4, mov, avi, mkv",
                        upload.originalFilename,
                        contentType,
                    ),
                )
            }
        }

        private fun normalizeObjectPrefix(prefix: String): String {
            if (prefix.isEmpty()) {
                return ""
            }
            if (!OBJECT_PREFIX.matches(prefix)) {
                throw IllegalArgumentException("저장 경로는 영문, 숫자, 하이픈, 밑줄과 경로 구분자로 구성해야 합니다.")
            }
            return if (prefix.endsWith("/")) prefix else "$prefix/"
        }

        private fun removeTrailingSlashes(url: String): String {
            var end = url.length
            while (end > 0 && url[end - 1] == '/') {
                end--
            }
            return url.substring(0, end)
        }
    }
}
