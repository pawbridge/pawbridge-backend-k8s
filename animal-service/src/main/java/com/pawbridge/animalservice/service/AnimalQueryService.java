package com.pawbridge.animalservice.service;

import com.pawbridge.animalservice.dto.response.AnimalResponse;
import com.pawbridge.animalservice.entity.Animal;
import com.pawbridge.animalservice.mapper.AnimalMapper;
import com.pawbridge.animalservice.repository.AnimalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 동물 조회 서비스 (MySQL)
 * - FeignClient용 단순 조회
 * - ID 기반 조회
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnimalQueryService {

    private final AnimalRepository animalRepository;
    private final AnimalMapper animalMapper;

    /**
     * 여러 ID로 동물 목록 일괄 조회 (MySQL)
     * - user-service의 찜 목록 조회에 사용
     * @param ids 동물 ID 목록
     * @return 동물 응답 목록
     */
    @Transactional(readOnly = true)
    public List<AnimalResponse> findByIds(List<Long> ids) {
        log.debug("[MySQL] 여러 ID로 조회: {}", ids);

        if (ids == null || ids.isEmpty()) {
            return new ArrayList<>();
        }

        // JPA의 findAllById 사용 (IN 쿼리)
        List<Animal> animals = animalRepository.findAllById(ids);

        return animals.stream()
                .map(animalMapper::toResponse)
                .collect(Collectors.toList());
    }
}
