package com.pawbridge.backend.store.internal.catalogwrite.infrastructure;

import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public final class S3ProductImages {

    private static final long MAX_IMAGE_SIZE_BYTES = 5L * 1024 * 1024;

    private final S3Client s3Client;
    private final String bucketName;
    private final String publicBaseUrl;

    private static final List<String> ALLOWED_IMAGE_TYPES = Arrays.asList(
            "image/jpeg",
            "image/jpg",
            "image/png",
            "image/gif",
            "image/webp"
    );

    public S3ProductImages(
            S3Client s3Client,
            String bucketName,
            String publicBaseUrl
    ) {
        this.s3Client = s3Client;
        this.bucketName = bucketName;
        this.publicBaseUrl = removeTrailingSlashes(publicBaseUrl);
    }

    public String uploadImage(MultipartFile file) {
        validateFileType(file);

        String originalFilename = file.getOriginalFilename();
        String extension = originalFilename != null && originalFilename.contains(".")
                ? originalFilename.substring(originalFilename.lastIndexOf("."))
                : "";

        // Store in 'products/' folder
        String key = "products/" + UUID.randomUUID() + extension;

        try {
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .contentType(file.getContentType())
                    .build();

            // Not fromInputStream: with chunked encoding disabled the SDK signs the payload by reading the
            // body once and then needs a second stream to send it. The part stream Tomcat provides for an
            // uploaded file (Files.newInputStream) cannot be reset, so the send would be empty while the
            // Content-Length is still announced and the storage waits for the rest. Bytes can be re-read.
            byte[] imageBytes = file.getBytes();
            s3Client.putObject(putObjectRequest, RequestBody.fromBytes(imageBytes));

            return publicBaseUrl + "/" + key;

        } catch (IOException e) {
            throw new RuntimeException("Failed to upload image", e);
        }
    }

    private void validateFileType(MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Image file must not be empty");
        }
        if (file.getSize() > MAX_IMAGE_SIZE_BYTES) {
            throw new IllegalArgumentException("Image file must not exceed 5MB");
        }

        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_IMAGE_TYPES.contains(contentType.toLowerCase())) {
            throw new IllegalArgumentException("Invalid file type. Allowed: " + ALLOWED_IMAGE_TYPES);
        }
    }

    private static String removeTrailingSlashes(String url) {
        int end = url.length();
        while (end > 0 && url.charAt(end - 1) == '/') {
            end--;
        }
        return url.substring(0, end);
    }
}
