package com.pawbridge.backend.animal.internal.image.infrastructure

import com.pawbridge.backend.animal.internal.command.infrastructure.service.AnimalPhotoStore
import org.springframework.web.multipart.MultipartFile
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import java.io.IOException
import java.util.Locale
import java.util.UUID

/**
 * Animal photo uploads and their cleanup; Root supplies the existing storage client and settings.
 * The bucket is shared with post, report and product media, so lookups and deletes only ever touch keys
 * shaped like the ones this class writes: `animals/<uuid v4>.<jpg|png|gif|webp>`.
 */
class S3AnimalImages(
    private val s3Client: S3Client,
    private val bucketName: String?,
    publicBaseUrl: String,
) : AnimalPhotoStore {
    private val publicBaseUrl: String = removeTrailingSlashes(publicBaseUrl)

    fun uploadImage(file: MultipartFile?): String {
        validateImageFile(file)

        // The extension follows the checked content type, not the client's file name, so every key keeps the
        // uploaded-photo shape that cleanup recognizes.
        val contentType = file!!.contentType!!.lowercase(Locale.getDefault())
        val extension = EXTENSIONS.getValue(contentType)
        val key = ANIMALS_FOLDER + UUID.randomUUID() + extension

        try {
            val putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .contentType(file.contentType)
                .build()

            // Not fromInputStream: with chunked encoding disabled the SDK signs the payload by reading the
            // body once and then needs a second stream to send it. The part stream Tomcat provides for an
            // uploaded file (Files.newInputStream) cannot be reset, so the send would be empty while the
            // Content-Length is still announced and the storage waits for the rest. Bytes can be re-read.
            val imageBytes = file.bytes
            s3Client.putObject(putObjectRequest, RequestBody.fromBytes(imageBytes))

            return publicBaseUrl + "/" + key
        } catch (e: IOException) {
            throw RuntimeException("이미지 업로드에 실패했습니다.", e)
        }
    }

    override fun isUploadedPhoto(url: String): Boolean {
        return objectKey(url) != null
    }

    override fun exists(url: String): Boolean {
        val key = objectKey(url) ?: return false
        val headRequest = HeadObjectRequest.builder()
            .bucket(bucketName)
            .key(key)
            .build()
        try {
            s3Client.headObject(headRequest)
            return true
        } catch (failure: S3Exception) {
            if (failure.statusCode() == 404) {
                return false
            }
            throw failure
        }
    }

    override fun delete(url: String) {
        val key = objectKey(url) ?: return
        val deleteRequest = DeleteObjectRequest.builder()
            .bucket(bucketName)
            .key(key)
            .build()
        s3Client.deleteObject(deleteRequest)
    }

    private fun validateImageFile(file: MultipartFile?) {
        if (file == null || file.isEmpty) {
            throw IllegalArgumentException("파일이 비어있습니다.")
        }

        val contentType = file.contentType
        // Java String.toLowerCase() uses the default locale; Kotlin lowercase() would use Locale.ROOT.
        if (contentType == null || !EXTENSIONS.containsKey(contentType.lowercase(Locale.getDefault()))) {
            throw IllegalArgumentException("허용되지 않는 파일 형식입니다. 허용: ${EXTENSIONS.keys.toList()}")
        }

        // 파일 크기 제한 (10MB)
        val maxSize = 10L * 1024 * 1024
        if (file.size > maxSize) {
            throw IllegalArgumentException("파일 크기는 10MB를 초과할 수 없습니다.")
        }
    }

    /** The storage key of an uploaded animal photo address, or null for any other address. */
    private fun objectKey(url: String): String? {
        val publicUrlPrefix = "$publicBaseUrl/"
        if (!url.startsWith(publicUrlPrefix)) {
            return null
        }
        val key = url.substring(publicUrlPrefix.length)
        if (!UPLOADED_KEY.matches(key)) {
            return null
        }
        return key
    }

    private companion object {
        private const val ANIMALS_FOLDER = "animals/"

        private val UPLOADED_KEY = Regex(
            "animals/[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\.(jpg|png|gif|webp)",
        )

        // Allowed content types, in the order the original service listed them, with the stored extension.
        private val EXTENSIONS = linkedMapOf(
            "image/jpeg" to ".jpg",
            "image/jpg" to ".jpg",
            "image/png" to ".png",
            "image/gif" to ".gif",
            "image/webp" to ".webp",
        )

        private fun removeTrailingSlashes(url: String): String {
            var end = url.length
            while (end > 0 && url[end - 1] == '/') {
                end--
            }
            return url.substring(0, end)
        }
    }
}
