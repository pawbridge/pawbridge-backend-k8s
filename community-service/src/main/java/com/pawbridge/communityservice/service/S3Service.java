package com.pawbridge.communityservice.service;

import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * S3 파일 업로드 서비스 인터페이스
 */
public interface S3Service {

    /**
     * 여러 이미지 파일을 S3에 업로드
     * @param files 업로드할 파일 배열 (nullable)
     * @return S3에 저장된 파일 URL 리스트
     */
    List<String> uploadImages(MultipartFile[] files);

    /**
     * S3에서 파일 삭제
     * @param fileUrl 삭제할 파일의 S3 URL
     */
    void deleteFile(String fileUrl);
}
