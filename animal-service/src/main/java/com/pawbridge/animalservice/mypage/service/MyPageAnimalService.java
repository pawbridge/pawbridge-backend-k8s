package com.pawbridge.animalservice.mypage.service;

import com.pawbridge.animalservice.dto.response.AnimalResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

/**
 * 마이페이지용 동물 조회 서비스
 * - FeignClient 전용 단순 조회
 * - MySQL 사용
 */
public interface MyPageAnimalService {

    /**
     * 여러 ID로 동물 목록 일괄 조회 (MySQL)
     * @param ids 동물 ID 목록
     * @return 동물 목록
     */
    List<AnimalResponse> findByIds(List<Long> ids);

    /**
     * 보호소별 동물 목록 조회 (MySQL)
     * @param shelterId 보호소 ID
     * @param pageable 페이징 정보
     * @return 동물 목록
     */
    Page<AnimalResponse> findByShelterId(Long shelterId, Pageable pageable);
}
