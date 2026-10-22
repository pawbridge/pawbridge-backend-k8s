package com.pawbridge.backend.animal.internal.image.infrastructure;

import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Original animal-service S3Service upload/delete; Root supplies the existing storage client and settings. */
public final class S3AnimalImages {

    private final S3Client s3Client;
    private final String bucketName;
    private final String publicBaseUrl;

    private static final String ANIMALS_FOLDER = "animals/";

    private static final List<String> ALLOWED_IMAGE_TYPES = Arrays.asList(
            "image/jpeg",
            "image/jpg",
            "image/png",
            "image/gif",
            "image/webp"
    );

    public S3AnimalImages(
            S3Client s3Client,
            String bucketName,
            String publicBaseUrl
    ) {
        this.s3Client = s3Client;
        this.bucketName = bucketName;
        this.publicBaseUrl = removeTrailingSlashes(publicBaseUrl);
    }

    public String uploadImage(MultipartFile file) {
        validateImageFile(file);

        String originalFilename = file.getOriginalFilename();
        String extension = extractExtension(originalFilename);

        // S3 키: animals/{uuid}.{extension}
        String key = ANIMALS_FOLDER + UUID.randomUUID() + extension;

        try {
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .contentType(file.getContentType())
                    .build();

            s3Client.putObject(putObjectRequest, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));

            return publicBaseUrl + "/" + key;

        } catch (IOException e) {
            throw new RuntimeException("이미지 업로드에 실패했습니다.", e);
        }
    }

    public void deleteImage(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            return;
        }

        try {
            String key = extractObjectKey(imageUrl);
            DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build();

            s3Client.deleteObject(deleteRequest);

        } catch (Exception e) {
            // 삭제 실패는 예외를 던지지 않음 (비즈니스 로직에 영향 없음)
        }
    }

    private void validateImageFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("파일이 비어있습니다.");
        }

        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_IMAGE_TYPES.contains(contentType.toLowerCase())) {
            throw new IllegalArgumentException("허용되지 않는 파일 형식입니다. 허용: " + ALLOWED_IMAGE_TYPES);
        }

        // 파일 크기 제한 (10MB)
        long maxSize = 10 * 1024 * 1024;
        if (file.getSize() > maxSize) {
            throw new IllegalArgumentException("파일 크기는 10MB를 초과할 수 없습니다.");
        }
    }

    private String extractExtension(String filename) {
        if (filename != null && filename.contains(".")) {
            return filename.substring(filename.lastIndexOf("."));
        }
        return "";
    }

    private String extractObjectKey(String imageUrl) {
        String publicUrlPrefix = publicBaseUrl + "/";
        if (!imageUrl.startsWith(publicUrlPrefix)) {
            throw new IllegalArgumentException("등록된 공개 저장소 URL이 아닙니다.");
        }

        String key = imageUrl.substring(publicUrlPrefix.length());
        if (key.isBlank()) {
            throw new IllegalArgumentException("삭제할 객체 키가 없습니다.");
        }
        return key;
    }

    private static String removeTrailingSlashes(String url) {
        int end = url.length();
        while (end > 0 && url.charAt(end - 1) == '/') {
            end--;
        }
        return url.substring(0, end);
    }
}
