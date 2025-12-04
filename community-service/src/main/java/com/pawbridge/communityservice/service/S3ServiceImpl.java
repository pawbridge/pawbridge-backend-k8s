package com.pawbridge.communityservice.service;

import com.pawbridge.communityservice.exception.InvalidImageFormatException;
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
import java.util.Arrays;
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

    // 허용된 파일 타입 (이미지 + 영상)
    private static final List<String> ALLOWED_IMAGE_TYPES = Arrays.asList(
            "image/jpeg",
            "image/jpg",
            "image/png",
            "image/gif",
            "image/webp"
    );

    private static final List<String> ALLOWED_VIDEO_TYPES = Arrays.asList(
            "video/mp4",
            "video/quicktime",    // .mov
            "video/x-msvideo",    // .avi
            "video/x-matroska"    // .mkv
    );

    /**
     * 여러 이미지/영상 파일을 S3에 업로드
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
        // 파일 타입 검증 (이미지 + 영상)
        validateFileType(file);

        // 고유한 파일명 생성 (파일 타입에 따라 경로 분리)
        String originalFilename = file.getOriginalFilename();
        String extension = originalFilename != null && originalFilename.contains(".")
                ? originalFilename.substring(originalFilename.lastIndexOf("."))
                : "";

        // 이미지와 영상 저장 경로 분리
        String contentType = file.getContentType();
        String folder = isVideoType(contentType) ? "posts/videos/" : "posts/images/";
        String uniqueFilename = folder + UUID.randomUUID() + extension;

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
     * 파일 타입 검증 (이미지 + 영상)
     */
    private void validateFileType(MultipartFile file) {
        String contentType = file.getContentType();

        if (contentType == null) {
            log.error("파일 타입을 확인할 수 없습니다. 파일명: {}", file.getOriginalFilename());
            throw new InvalidImageFormatException(
                    String.format("파일 타입을 확인할 수 없습니다. (파일: %s)",
                            file.getOriginalFilename())
            );
        }

        String lowerContentType = contentType.toLowerCase();
        boolean isAllowedType = ALLOWED_IMAGE_TYPES.contains(lowerContentType)
                || ALLOWED_VIDEO_TYPES.contains(lowerContentType);

        if (!isAllowedType) {
            log.error("지원하지 않는 파일 형식: {}, 파일명: {}",
                    contentType, file.getOriginalFilename());
            throw new InvalidImageFormatException(
                    String.format("지원하지 않는 파일 형식입니다. (업로드한 파일: %s, 타입: %s). " +
                                    "허용된 형식 - 이미지: jpg, jpeg, png, gif, webp / 영상: mp4, mov, avi, mkv",
                            file.getOriginalFilename(), contentType)
            );
        }

        log.debug("파일 타입 검증 성공: {}", contentType);
    }

    /**
     * 영상 타입인지 확인
     */
    private boolean isVideoType(String contentType) {
        if (contentType == null) {
            return false;
        }
        return ALLOWED_VIDEO_TYPES.contains(contentType.toLowerCase());
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
