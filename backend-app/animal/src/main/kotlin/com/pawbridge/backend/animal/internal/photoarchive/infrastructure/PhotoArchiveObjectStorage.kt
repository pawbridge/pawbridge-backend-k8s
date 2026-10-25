package com.pawbridge.backend.animal.internal.photoarchive.infrastructure

import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.core.sync.ResponseTransformer
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import java.time.Duration

class PhotoArchiveObjectStorage(
    private val s3: S3Client,
    private val bucket: String?,
) : AutoCloseable {

    fun saveAndVerify(photo: ArchivedPhoto) {
        if (matchesExisting(photo)) {
            return
        }
        s3.putObject(
            PutObjectRequest.builder()
                .bucket(bucket)
                .key(photo.objectKey())
                .contentType(photo.contentType)
                .overrideConfiguration { configuration ->
                    configuration
                        .apiCallTimeout(Duration.ofSeconds(30))
                        .apiCallAttemptTimeout(Duration.ofSeconds(15))
                }
                .build(),
            RequestBody.fromBytes(photo.bytes),
        )
        if (!matchesExisting(photo)) {
            throw PhotoArchiveFailure("STORAGE_NOT_VISIBLE", false)
        }
    }

    override fun close() {
        s3.close()
    }

    private fun matchesExisting(photo: ArchivedPhoto): Boolean {
        try {
            return s3.getObject(
                GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(photo.objectKey())
                    .overrideConfiguration { configuration ->
                        configuration
                            .apiCallTimeout(Duration.ofSeconds(30))
                            .apiCallAttemptTimeout(Duration.ofSeconds(15))
                    }
                    .build(),
                ResponseTransformer<GetObjectResponse, Boolean> { response, stream ->
                    if (response.contentLength() == null ||
                        response.contentLength() != photo.bytes.size.toLong() ||
                        photo.contentType != response.contentType()
                    ) {
                        throw PhotoArchiveFailure("STORAGE_INTEGRITY", true)
                    }
                    val bytes = stream.readNBytes(ArchivedPhoto.MAX_BYTES + 1)
                    if (bytes.size != photo.bytes.size || ArchivedPhoto.sha256(bytes) != photo.storedHash) {
                        throw PhotoArchiveFailure("STORAGE_INTEGRITY", true)
                    }
                    true
                },
            )
        } catch (failure: S3Exception) {
            if (failure.statusCode() == 404) {
                return false
            }
            throw failure
        }
    }
}
