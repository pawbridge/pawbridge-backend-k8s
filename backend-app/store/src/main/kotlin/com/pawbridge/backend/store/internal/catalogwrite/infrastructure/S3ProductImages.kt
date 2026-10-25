package com.pawbridge.backend.store.internal.catalogwrite.infrastructure

import org.springframework.web.multipart.MultipartFile
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.io.IOException
import java.util.Locale
import java.util.UUID

class S3ProductImages(
    private val s3Client: S3Client?,
    private val bucketName: String?,
    publicBaseUrl: String,
) {

    private val publicBaseUrl: String = removeTrailingSlashes(publicBaseUrl)

    fun uploadImage(file: MultipartFile): String {
        validateFileType(file)

        val originalFilename = file.originalFilename
        val extension = if (originalFilename != null && originalFilename.contains("."))
            originalFilename.substring(originalFilename.lastIndexOf("."))
        else
            ""

        // Store in 'products/' folder
        val key = "products/" + UUID.randomUUID() + extension

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
            s3Client!!.putObject(putObjectRequest, RequestBody.fromBytes(imageBytes))

            return publicBaseUrl + "/" + key
        } catch (e: IOException) {
            throw RuntimeException("Failed to upload image", e)
        }
    }

    private fun validateFileType(file: MultipartFile) {
        if (file.isEmpty) {
            throw IllegalArgumentException("Image file must not be empty")
        }
        if (file.size > MAX_IMAGE_SIZE_BYTES) {
            throw IllegalArgumentException("Image file must not exceed 5MB")
        }

        val contentType = file.contentType
        // Java String.toLowerCase() uses the default locale, so this does too.
        if (contentType == null || !ALLOWED_IMAGE_TYPES.contains(contentType.lowercase(Locale.getDefault()))) {
            throw IllegalArgumentException("Invalid file type. Allowed: $ALLOWED_IMAGE_TYPES")
        }
    }

    private companion object {
        private const val MAX_IMAGE_SIZE_BYTES = 5L * 1024 * 1024

        private val ALLOWED_IMAGE_TYPES = listOf(
            "image/jpeg",
            "image/jpg",
            "image/png",
            "image/gif",
            "image/webp",
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
