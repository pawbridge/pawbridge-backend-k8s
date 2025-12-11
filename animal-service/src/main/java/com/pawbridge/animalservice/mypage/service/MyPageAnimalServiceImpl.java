package com.pawbridge.animalservice.mypage.service;

import com.pawbridge.animalservice.dto.response.AnimalResponse;
import com.pawbridge.animalservice.entity.Animal;
import com.pawbridge.animalservice.mapper.AnimalMapper;
import com.pawbridge.animalservice.repository.AnimalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 마이페이지용 동물 조회 서비스 구현
 * - FeignClient 전용 단순 조회
 * - MySQL 사용
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MyPageAnimalServiceImpl implements MyPageAnimalService {

    private final AnimalRepository animalRepository;
    private final AnimalMapper animalMapper;

    /**
     * 여러 ID로 동물 목록 일괄 조회 (MySQL)
     * - user-service의 찜 목록 조회에 사용
     */
    @Override
    @Transactional(readOnly = true)
    public List<AnimalResponse> findByIds(List<Long> ids) {
        log.debug("[MyPage] 여러 ID로 조회: {}", ids);

        if (ids == null || ids.isEmpty()) {
            return new ArrayList<>();
        }

        // JPA의 findAllById 사용 (IN 쿼리)
        List<Animal> animals = animalRepository.findAllById(ids);

        return animals.stream()
                .map(animalMapper::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * 보호소별 동물 목록 조회 (MySQL)
     * - user-service의 보호소 직원 마이페이지에 사용
     */
    @Override
    @Transactional(readOnly = true)
    public Page<AnimalResponse> findByShelterId(Long shelterId, Pageable pageable) {
        log.debug("[MyPage] 보호소별 동물 조회: shelterId={}", shelterId);

        Page<Animal> animals = animalRepository.findByShelterId(shelterId, pageable);

        return animals.map(animalMapper::toResponse);
    }
}
