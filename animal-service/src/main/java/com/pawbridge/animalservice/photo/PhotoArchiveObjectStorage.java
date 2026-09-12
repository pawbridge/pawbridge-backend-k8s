package com.pawbridge.animalservice.photo;

import java.time.Duration;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

public class PhotoArchiveObjectStorage {
    private final S3Client s3;
    private final String bucket;
    public PhotoArchiveObjectStorage(S3Client s3, String bucket) { this.s3 = s3; this.bucket = bucket; }

    public void saveAndVerify(ArchivedPhoto photo) {
        if (matchesExisting(photo)) return;
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key(photo.objectKey()).contentType(photo.contentType())
                        .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(30)).apiCallAttemptTimeout(Duration.ofSeconds(15)))
                        .build(), RequestBody.fromBytes(photo.bytes()));
        if (!matchesExisting(photo)) throw new PhotoArchiveFailure("STORAGE_NOT_VISIBLE", false);
    }

    private boolean matchesExisting(ArchivedPhoto photo) {
        try {
            return s3.getObject(GetObjectRequest.builder().bucket(bucket).key(photo.objectKey())
                    .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(30)).apiCallAttemptTimeout(Duration.ofSeconds(15)))
                    .build(), (response, stream) -> {
                if (response.contentLength() == null || response.contentLength() != photo.bytes().length
                        || !photo.contentType().equals(response.contentType()))
                    throw new PhotoArchiveFailure("STORAGE_INTEGRITY", true);
                byte[] bytes = stream.readNBytes(ArchivedPhoto.MAX_BYTES + 1);
                if (bytes.length != photo.bytes().length || !ArchivedPhoto.sha256(bytes).equals(photo.storedHash()))
                    throw new PhotoArchiveFailure("STORAGE_INTEGRITY", true);
                return true;
            });
        } catch (S3Exception failure) {
            if (failure.statusCode() == 404) return false;
            throw failure;
        }
    }
}
