package com.pawbridge.backend.animal.internal.image.infrastructure

import org.springframework.web.multipart.MultipartFile
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.io.IOException
import java.util.Locale
import java.util.UUID

/** Original animal-service S3Service upload/delete; Root supplies the existing storage client and settings. */
class S3AnimalImages(
    private val s3Client: S3Client,
    private val bucketName: String?,
    publicBaseUrl: String,
) {
    private val publicBaseUrl: String = removeTrailingSlashes(publicBaseUrl)

    fun uploadImage(file: MultipartFile?): String {
        validateImageFile(file)

        val originalFilename = file!!.originalFilename
        val extension = extractExtension(originalFilename)

        // S3 키: animals/{uuid}.{extension}
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

    fun deleteImage(imageUrl: String?) {
        if (imageUrl == null || isBlank(imageUrl)) {
            return
        }

        try {
            val key = extractObjectKey(imageUrl)
            val deleteRequest = DeleteObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build()

            s3Client.deleteObject(deleteRequest)
        } catch (e: Exception) {
            // 삭제 실패는 예외를 던지지 않음 (비즈니스 로직에 영향 없음)
        }
    }

    private fun validateImageFile(file: MultipartFile?) {
        if (file == null || file.isEmpty) {
            throw IllegalArgumentException("파일이 비어있습니다.")
        }

        val contentType = file.contentType
        // Java String.toLowerCase() uses the default locale; Kotlin lowercase() would use Locale.ROOT.
        if (contentType == null || !ALLOWED_IMAGE_TYPES.contains(contentType.lowercase(Locale.getDefault()))) {
            throw IllegalArgumentException("허용되지 않는 파일 형식입니다. 허용: $ALLOWED_IMAGE_TYPES")
        }

        // 파일 크기 제한 (10MB)
        val maxSize = 10L * 1024 * 1024
        if (file.size > maxSize) {
            throw IllegalArgumentException("파일 크기는 10MB를 초과할 수 없습니다.")
        }
    }

    private fun extractExtension(filename: String?): String {
        if (filename != null && filename.contains(".")) {
            return filename.substring(filename.lastIndexOf("."))
        }
        return ""
    }

    private fun extractObjectKey(imageUrl: String): String {
        val publicUrlPrefix = "$publicBaseUrl/"
        if (!imageUrl.startsWith(publicUrlPrefix)) {
            throw IllegalArgumentException("등록된 공개 저장소 URL이 아닙니다.")
        }

        val key = imageUrl.substring(publicUrlPrefix.length)
        if (isBlank(key)) {
            throw IllegalArgumentException("삭제할 객체 키가 없습니다.")
        }
        return key
    }

    private companion object {
        private const val ANIMALS_FOLDER = "animals/"

        private val ALLOWED_IMAGE_TYPES = listOf(
            "image/jpeg",
            "image/jpg",
            "image/png",
            "image/gif",
            "image/webp",
        )

        // Java String.isBlank() uses Character.isWhitespace; Kotlin isBlank() also accepts NBSP.
        private fun isBlank(value: String): Boolean = value.all { Character.isWhitespace(it) }

        private fun removeTrailingSlashes(url: String): String {
            var end = url.length
            while (end > 0 && url[end - 1] == '/') {
                end--
            }
            return url.substring(0, end)
        }
    }
}
