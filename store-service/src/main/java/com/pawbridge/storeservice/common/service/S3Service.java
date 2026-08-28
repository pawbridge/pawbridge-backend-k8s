package com.pawbridge.storeservice.common.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class S3Service {

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

    public S3Service(
            S3Client s3Client,
            @Value("${spring.cloud.aws.s3.bucket}") String bucketName,
            @Value("${pawbridge.storage.public-base-url}") String publicBaseUrl
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

            s3Client.putObject(putObjectRequest, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));

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
