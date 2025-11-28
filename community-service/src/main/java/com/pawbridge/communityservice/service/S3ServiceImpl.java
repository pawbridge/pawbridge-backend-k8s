package com.pawbridge.communityservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * S3 파일 업로드 서비스 구현체
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class S3ServiceImpl implements S3Service {

    private final S3Client s3Client;

    @Value("${spring.cloud.aws.s3.bucket}")
    private String bucketName;

    /**
     * 여러 이미지 파일을 S3에 업로드
     */
    @Override
    public List<String> uploadImages(MultipartFile[] files) {
        List<String> uploadedUrls = new ArrayList<>();

        if (files == null || files.length == 0) {
            return uploadedUrls;
        }

        for (MultipartFile file : files) {
            if (file.isEmpty()) {
                continue;
            }

            try {
                String uploadedUrl = uploadSingleFile(file);
                uploadedUrls.add(uploadedUrl);
            } catch (IOException e) {
                log.error("파일 업로드 실패: {}", file.getOriginalFilename(), e);
                throw new RuntimeException("파일 업로드 중 오류가 발생했습니다", e);
            }
        }

        return uploadedUrls;
    }

    /**
     * 단일 파일을 S3에 업로드
     */
    private String uploadSingleFile(MultipartFile file) throws IOException {
        // 고유한 파일명 생성 (UUID + 원본 파일명)
        String originalFilename = file.getOriginalFilename();
        String extension = originalFilename != null && originalFilename.contains(".")
                ? originalFilename.substring(originalFilename.lastIndexOf("."))
                : "";
        String uniqueFilename = "posts/" + UUID.randomUUID() + extension;

        // S3에 업로드
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(uniqueFilename)
                .contentType(file.getContentType())
                .build();

        s3Client.putObject(putObjectRequest, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));

        // 업로드된 파일의 URL 반환
        String fileUrl = String.format("https://%s.s3.%s.amazonaws.com/%s",
                bucketName, "ap-northeast-2", uniqueFilename);

        log.info("파일 업로드 성공: {}", fileUrl);
        return fileUrl;
    }

    /**
     * S3에서 파일 삭제
     */
    @Override
    public void deleteFile(String fileUrl) {
        try {
            // URL에서 키 추출 (예: https://bucket.s3.region.amazonaws.com/posts/uuid.jpg -> posts/uuid.jpg)
            String key = fileUrl.substring(fileUrl.indexOf(".com/") + 5);

            DeleteObjectRequest deleteObjectRequest = DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build();

            s3Client.deleteObject(deleteObjectRequest);
            log.info("파일 삭제 성공: {}", fileUrl);

        } catch (Exception e) {
            log.error("파일 삭제 실패: {}", fileUrl, e);
        }
    }
}
