package com.pawbridge.animalservice.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * S3 이미지 업로드 서비스
 * - 동물 이미지 업로드/삭제 기능 제공
 */
@Slf4j
@Service
public class S3Service {

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

    public S3Service(
            S3Client s3Client,
            @Value("${spring.cloud.aws.s3.bucket}") String bucketName,
            @Value("${pawbridge.storage.public-base-url}") String publicBaseUrl
    ) {
        this.s3Client = s3Client;
        this.bucketName = bucketName;
        this.publicBaseUrl = removeTrailingSlashes(publicBaseUrl);
    }

    /**
     * 이미지 업로드
     * @param file 업로드할 이미지 파일
     * @return 업로드된 이미지의 S3 URL
     */
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

            String imageUrl = publicBaseUrl + "/" + key;
            log.info("이미지 업로드 완료: {}", imageUrl);
            return imageUrl;

        } catch (IOException e) {
            log.error("이미지 업로드 실패: {}", originalFilename, e);
            throw new RuntimeException("이미지 업로드에 실패했습니다.", e);
        }
    }

    /**
     * 이미지 삭제
     * @param imageUrl 삭제할 이미지의 S3 URL
     */
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
            log.info("이미지 삭제 완료: {}", key);

        } catch (Exception e) {
            log.error("이미지 삭제 실패: {}", imageUrl, e);
            // 삭제 실패는 예외를 던지지 않음 (비즈니스 로직에 영향 없음)
        }
    }

    /**
     * 파일 유효성 검증
     */
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

    /**
     * 파일 확장자 추출
     */
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
